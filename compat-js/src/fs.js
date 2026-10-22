/**
 * Plugins get no file system. Bundled libraries may still require('fs') at load
 * time (qrcode does, for toFile), so requiring succeeds and only calls fail.
 */
const unavailable = (name) => () => {
  throw new Error(`fs.${name} is not available to LyricsAdapter plugins`);
};

export const fsModule = new Proxy(Object.create(null), {
  get(_target, property) {
    if (typeof property === 'symbol' || property === '__esModule' || property === 'default') return undefined;
    return unavailable(property);
  },
});
