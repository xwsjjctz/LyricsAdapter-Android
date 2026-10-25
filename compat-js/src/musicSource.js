/**
 * The calls a music source answers, shared by legacy `createPlugin(host)`
 * plugins and music sources registered on the platform API. They mirror the
 * desktop app's electron/services/pluginHandle.ts (LyricsAdapter repository):
 * the same call whitelist, argument limit, export validation and
 * coverUrlFullSize mapping.
 */
const PROVIDER_METHODS = new Set(['searchMusic', 'getRecommendedSongs', 'getSongDetails', 'getMusicUrl', 'getLyrics', 'getPlaylists', 'getPlaylistSongs']);
const SONG_LIST_METHODS = new Set(['searchMusic', 'getRecommendedSongs', 'getSongDetails', 'getPlaylistSongs']);
const REQUIRED_PROVIDER_METHODS = ['searchMusic', 'getRecommendedSongs', 'getMusicUrl', 'getLyrics', 'getPlaylists', 'getPlaylistSongs', 'requiresCookie'];
const MAX_CALL_ARGS = 5;

export function describe(error) {
  if (error instanceof Error) return error.message || error.name;
  return String(error);
}

export function validateMusicSource(plugin, id) {
  if (!plugin?.provider || plugin.provider.id !== id || typeof plugin.invoke !== 'function'
    || typeof plugin.streamHeaders !== 'function' || typeof plugin.validateCookie !== 'function') {
    throw new Error('Invalid music plugin exports');
  }
  for (const method of REQUIRED_PROVIDER_METHODS) {
    if (typeof plugin.provider[method] !== 'function') throw new Error(`Missing provider method: ${method}`);
  }
}

/** Answers one music request ([operation] with its [payload]) from [plugin]. */
export async function musicDispatch(plugin, operation, payload) {
  switch (operation) {
    case 'call':
      return callProvider(plugin, payload.method, payload.args);
    case 'invoke':
      if (!Array.isArray(payload.args)) throw new Error('Invalid plugin invoke arguments');
      return plugin.invoke(String(payload.action), payload.args);
    case 'streamHeaders':
      return plugin.streamHeaders(String(payload.cookie));
    case 'requiresCookie':
      return Boolean(plugin.provider.requiresCookie());
    default:
      return undefined;
  }
}

export const MUSIC_OPERATIONS = new Set(['call', 'invoke', 'streamHeaders', 'requiresCookie']);

async function callProvider(current, method, args) {
  if (!Array.isArray(args) || args.length > MAX_CALL_ARGS) throw new Error('Invalid plugin call arguments');
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

