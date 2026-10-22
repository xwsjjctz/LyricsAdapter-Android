/**
 * The subset of Node's `process` that bundled plugin dependencies touch.
 * nextTick runs on the microtask queue, which QuickJS drains between host jobs.
 */
const startedAt = Date.now();

function nextTick(callback, ...args) {
  if (typeof callback !== 'function') throw new TypeError('The "callback" argument must be of type function');
  Promise.resolve().then(() => callback(...args));
}

function hrtime(previous) {
  const elapsed = Date.now() - startedAt;
  const seconds = Math.floor(elapsed / 1000);
  const nanoseconds = (elapsed % 1000) * 1e6;
  if (!previous) return [seconds, nanoseconds];
  const deltaNanoseconds = nanoseconds - previous[1];
  return deltaNanoseconds < 0
    ? [seconds - previous[0] - 1, deltaNanoseconds + 1e9]
    : [seconds - previous[0], deltaNanoseconds];
}
hrtime.bigint = () => BigInt(Date.now() - startedAt) * 1000000n;

const unsupported = (name) => () => {
  throw new Error(`process.${name}() is not available to plugins`);
};

export const processShim = {
  title: 'lyricsadapter',
  env: {},
  argv: [],
  execArgv: [],
  version: 'v22.0.0',
  versions: { node: '22.0.0' },
  release: { name: 'node' },
  platform: 'android',
  arch: 'arm64',
  pid: 1,
  ppid: 0,
  cwd: () => '/',
  umask: () => 0o22,
  nextTick,
  hrtime,
  uptime: () => (Date.now() - startedAt) / 1000,
  memoryUsage: () => ({ rss: 0, heapTotal: 0, heapUsed: 0, external: 0, arrayBuffers: 0 }),
  emitWarning: (warning) => console.warn(warning instanceof Error ? warning.message : String(warning)),
  chdir: unsupported('chdir'),
  exit: unsupported('exit'),
  abort: unsupported('abort'),
  binding: unsupported('binding'),
  on: () => processShim,
  once: () => processShim,
  off: () => processShim,
  addListener: () => processShim,
  removeListener: () => processShim,
  removeAllListeners: () => processShim,
  prependListener: () => processShim,
  prependOnceListener: () => processShim,
  emit: () => false,
  listeners: () => [],
  listenerCount: () => 0,
};
