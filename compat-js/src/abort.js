const LEGACY_ERROR_CODES = { AbortError: 20, TimeoutError: 23, NotSupportedError: 9, InvalidStateError: 11 };

export function createAbortApi(timers) {
  class DOMException extends Error {
    constructor(message = '', name = 'Error') {
      super(message);
      this.name = name;
    }

    get code() {
      return LEGACY_ERROR_CODES[this.name] ?? 0;
    }
  }

  class Event {
    constructor(type, init = {}) {
      this.type = String(type);
      this.bubbles = Boolean(init.bubbles);
      this.cancelable = Boolean(init.cancelable);
      this.defaultPrevented = false;
      this.timeStamp = Date.now();
      this.target = null;
      this.currentTarget = null;
    }

    preventDefault() {
      if (this.cancelable) this.defaultPrevented = true;
    }

    stopPropagation() {}

    stopImmediatePropagation() {
      this._stopped = true;
    }
  }

  class EventTarget {
    constructor() {
      this._listeners = new Map();
    }

    addEventListener(type, listener, options) {
      if (listener == null) return;
      const once = typeof options === 'object' && options !== null && Boolean(options.once);
      const listeners = this._listeners.get(type) ?? [];
      if (listeners.some((entry) => entry.listener === listener)) return;
      this._listeners.set(type, [...listeners, { listener, once }]);
    }

    removeEventListener(type, listener) {
      const listeners = this._listeners.get(type);
      if (listeners) this._listeners.set(type, listeners.filter((entry) => entry.listener !== listener));
    }

    dispatchEvent(event) {
      event.target = this;
      event.currentTarget = this;
      const handler = this[`on${event.type}`];
      if (typeof handler === 'function') handler.call(this, event);
      for (const entry of this._listeners.get(event.type) ?? []) {
        if (entry.once) this.removeEventListener(event.type, entry.listener);
        if (typeof entry.listener === 'function') entry.listener.call(this, event);
        else entry.listener?.handleEvent?.(event);
        if (event._stopped) break;
      }
      return !event.defaultPrevented;
    }
  }

  const abortError = () => new DOMException('This operation was aborted', 'AbortError');

  class AbortSignal extends EventTarget {
    constructor() {
      super();
      this.aborted = false;
      this.reason = undefined;
      this.onabort = null;
    }

    throwIfAborted() {
      if (this.aborted) throw this.reason;
    }

    static abort(reason) {
      const controller = new AbortController();
      controller.abort(reason);
      return controller.signal;
    }

    static timeout(milliseconds) {
      const controller = new AbortController();
      timers.setTimeout(() => controller.abort(new DOMException('The operation was aborted due to timeout', 'TimeoutError')), milliseconds);
      return controller.signal;
    }

    static any(signals) {
      const controller = new AbortController();
      const aborted = signals.find((signal) => signal.aborted);
      if (aborted) {
        controller.abort(aborted.reason);
        return controller.signal;
      }
      for (const signal of signals) signal.addEventListener('abort', () => controller.abort(signal.reason), { once: true });
      return controller.signal;
    }
  }

  class AbortController {
    constructor() {
      this.signal = new AbortSignal();
    }

    abort(reason) {
      const signal = this.signal;
      if (signal.aborted) return;
      signal.aborted = true;
      signal.reason = reason === undefined ? abortError() : reason;
      signal.dispatchEvent(new Event('abort'));
    }
  }

  return { AbortController, AbortSignal, DOMException, Event, EventTarget, abortError };
}
