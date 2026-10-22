/**
 * Hosts one legacy `createPlugin(host)` music plugin, mirroring the desktop
 * app's electron/services/pluginHandle.ts (LyricsAdapter repository): the same
 * call whitelist, argument limit, export validation and coverUrlFullSize mapping.
 *
 * serve() is the runtime's event loop. Each request is handled without being
 * awaited, so several calls can be in flight while their fetches run.
 */
const PROVIDER_METHODS = new Set(['searchMusic', 'getRecommendedSongs', 'getSongDetails', 'getMusicUrl', 'getLyrics', 'getPlaylists', 'getPlaylistSongs']);
const SONG_LIST_METHODS = new Set(['searchMusic', 'getRecommendedSongs', 'getSongDetails', 'getPlaylistSongs']);
const REQUIRED_PROVIDER_METHODS = ['searchMusic', 'getRecommendedSongs', 'getMusicUrl', 'getLyrics', 'getPlaylists', 'getPlaylistSongs', 'requiresCookie'];
const MAX_CALL_ARGS = 5;

function describe(error) {
  if (error instanceof Error) return error.message || error.name;
  return String(error);
}

function validate(plugin, id) {
  if (!plugin?.provider || plugin.provider.id !== id || typeof plugin.invoke !== 'function'
    || typeof plugin.streamHeaders !== 'function' || typeof plugin.validateCookie !== 'function') {
    throw new Error('Invalid music plugin exports');
  }
  for (const method of REQUIRED_PROVIDER_METHODS) {
    if (typeof plugin.provider[method] !== 'function') throw new Error(`Missing provider method: ${method}`);
  }
}

export function createLegacyRuntime({ pluginId, host, requireModule, format }) {
  let moduleExports;
  let plugin;

  const log = (level) => (...args) => host.log(level, format(...args));
  const pluginHost = {
    logger: { debug: log('debug'), info: log('info'), warn: log('warn'), error: log('error') },
    readSecret: (name) => host.readSecret(String(name)),
    writeSecrets: (entries) => host.writeSecrets(JSON.stringify(entries ?? {})),
    readSetting: (key) => host.readSetting(String(key)) ?? undefined,
    writeSetting: (key, value) => host.writeSetting(String(key), value === undefined ? null : String(value)),
  };

  const loaded = () => {
    if (!plugin) throw new Error('Plugin is not loaded');
    return plugin;
  };

  function define(factory) {
    const module = { exports: {} };
    factory.call(module.exports, module.exports, requireModule, module, `/plugins/${pluginId}/index.cjs`, `/plugins/${pluginId}`);
    moduleExports = module.exports;
  }

  function create() {
    if (typeof moduleExports?.createPlugin !== 'function') throw new Error('Plugin must export createPlugin');
    const created = moduleExports.createPlugin(pluginHost);
    validate(created, pluginId);
    plugin = created;
  }

  async function call(method, args) {
    if (!Array.isArray(args) || args.length > MAX_CALL_ARGS) throw new Error('Invalid plugin call arguments');
    const current = loaded();
    if (method === 'validateCookie') {
      if (args.length !== 1 || typeof args[0] !== 'string') throw new Error('Invalid cookie validation call');
      return current.validateCookie(args[0]);
    }
    const provider = current.provider;
    if (!PROVIDER_METHODS.has(method) || typeof provider[method] !== 'function') throw new Error(`Unsupported provider method: ${method}`);
    const result = await provider[method](...args);
    if (SONG_LIST_METHODS.has(method) && Array.isArray(result) && typeof provider.getCoverUrl === 'function') {
      return result.map((song) => (song && typeof song === 'object' ? { ...song, coverUrlFullSize: provider.getCoverUrl(song) } : song));
    }
    return result;
  }

  async function dispatch(operation, payload) {
    switch (operation) {
      case 'call':
        return call(payload.method, payload.args);
      case 'invoke':
        if (!Array.isArray(payload.args)) throw new Error('Invalid plugin invoke arguments');
        return loaded().invoke(String(payload.action), payload.args);
      case 'streamHeaders':
        return loaded().streamHeaders(String(payload.cookie));
      case 'requiresCookie':
        return Boolean(loaded().provider.requiresCookie());
      default:
        throw new Error(`Unknown plugin operation: ${operation}`);
    }
  }

  function respond(id, value) {
    let json;
    try {
      json = JSON.stringify(value === undefined ? null : value);
    } catch (error) {
      host.complete(id, false, describe(error));
      return;
    }
    host.complete(id, true, json);
  }

  async function serve() {
    for (;;) {
      const request = await host.nextRequest();
      if (request == null) return;
      const [id, operation, payloadJson] = request;
      Promise.resolve()
        .then(() => dispatch(operation, JSON.parse(payloadJson)))
        .then((value) => respond(id, value), (error) => host.complete(id, false, describe(error)))
        .catch((error) => host.log('error', `Plugin response failed: ${describe(error)}`));
    }
  }

  return { define, create, serve };
}
