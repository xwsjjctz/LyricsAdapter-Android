/**
 * Node-style timers on top of the host's cancellable sleep. Clearing a timer
 * wakes its sleep at once, so a cleared timer never keeps the runtime busy.
 */
const MAX_DELAY = 2 ** 31 - 1;

class Timeout {
  constructor(id, clear) {
    this._id = id;
    this._clear = clear;
  }

  ref() {
    return this;
  }

  unref() {
    return this;
  }

  hasRef() {
    return true;
  }

  refresh() {
    return this;
  }

  close() {
    this._clear(this);
    return this;
  }

  [Symbol.toPrimitive]() {
    return this._id;
  }
}

function delayOf(delay) {
  const milliseconds = Number(delay);
  return Number.isFinite(milliseconds) && milliseconds > 0 ? Math.min(Math.floor(milliseconds), MAX_DELAY) : 0;
}

function requireCallback(callback) {
  if (typeof callback !== 'function') throw new TypeError('The "callback" argument must be of type function');
}

export function createTimers(host, reportError) {
  let nextId = 1;
  const active = new Set();

  const run = (callback, args) => {
    try {
      callback(...args);
    } catch (error) {
      reportError(error);
    }
  };

  function clearTimer(handle) {
    if (handle == null) return;
    const id = handle instanceof Timeout ? handle._id : Number(handle);
    if (active.delete(id)) host.cancelSleep(id);
  }

  function setTimeoutImpl(callback, delay, ...args) {
    requireCallback(callback);
    const id = nextId++;
    active.add(id);
    host.sleep(id, delayOf(delay)).then(() => {
      if (active.delete(id)) run(callback, args);
    });
    return new Timeout(id, clearTimer);
  }

  function setIntervalImpl(callback, delay, ...args) {
    requireCallback(callback);
    const id = nextId++;
    const interval = Math.max(1, delayOf(delay));
    active.add(id);
    const tick = () => host.sleep(id, interval).then(() => {
      if (!active.has(id)) return;
      run(callback, args);
      if (active.has(id)) tick();
    });
    tick();
    return new Timeout(id, clearTimer);
  }

  return {
    setTimeout: setTimeoutImpl,
    clearTimeout: clearTimer,
    setInterval: setIntervalImpl,
    clearInterval: clearTimer,
    setImmediate: (callback, ...args) => setTimeoutImpl(callback, 0, ...args),
    clearImmediate: clearTimer,
  };
}
