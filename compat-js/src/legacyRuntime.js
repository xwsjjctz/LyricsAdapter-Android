/**
 * Hosts one legacy `createPlugin(host)` music plugin; its calls are answered
 * as musicSource.js answers them, following desktop's pluginHandle.ts.
 *
 * serve() is the runtime's event loop. Each request is handled without being
 * awaited, so several calls can be in flight while their fetches run.
 */
import { describe, MUSIC_OPERATIONS, musicDispatch, validateMusicSource } from './musicSource.js';

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
    validateMusicSource(created, pluginId);
    plugin = created;
  }

  async function dispatch(operation, payload) {
    if (MUSIC_OPERATIONS.has(operation)) return musicDispatch(loaded(), operation, payload);
    throw new Error(`Unknown plugin operation: ${operation}`);
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
