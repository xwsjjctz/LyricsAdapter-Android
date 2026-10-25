/**
 * Hosts one plugin on desktop's plugin platform (`manifestVersion: 1`,
 * `activate(context)`), following the contract in the desktop app's
 * src/shared/plugin.ts and electron/services/pluginRuntime.ts (LyricsAdapter
 * repository). Android supports the music.source and lyrics.translation
 * extensions; a music source answers the same calls as a legacy plugin.
 *
 * The host answers what JavaScript cannot decide alone: configuration values
 * (secret fields included), the origins the plugin may reach, and the
 * plugin's file-backed cache. serve() is the runtime's event loop.
 */
import { describe, MUSIC_OPERATIONS, musicDispatch, validateMusicSource } from './musicSource.js';
const DATA_KEY = /^[a-z][a-z0-9-]{0,63}$/;
const DEFAULT_FETCH_TIMEOUT_MS = 20000;
const MAX_FETCH_TIMEOUT_MS = 120000;
const MAX_BODY_BYTES = 1000000;
const MAX_STORAGE_JSON = 65536;
const MAX_SECRET_LENGTH = 65536;
const MAX_CONCURRENT_CALLS = 4;
const EXTENSION_APIS = { 'music.source': '1.0.0', 'lyrics.translation': '1.2.0' };
const CORE_API = '1.1.0';


function utf8Length(text) {
  return new TextEncoder().encode(text).length;
}

/** `scheme://host[:port]` of an https URL, or of http on loopback; undefined for anything else. */
function networkOrigin(value) {
  try {
    const url = new URL(value);
    if (url.username || url.password) return undefined;
    const loopback = url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]';
    if (url.protocol === 'https:' || (url.protocol === 'http:' && loopback)) return url.origin;
  } catch {
    // not a URL
  }
  return undefined;
}

export function createPlatformRuntime({ pluginId, host, requireModule, format, abortApi, timers }) {
  const { AbortController } = abortApi;
  const lifetime = new AbortController();
  const providers = new Map();
  const subscriptions = [];
  const configurationListeners = new Set();
  const calls = new Map();
  let moduleExports;
  let manifest;
  let activeCalls = 0;

  const log = (level) => (...args) => host.log(level, format(...args));
  const logger = { debug: log('debug'), info: log('info'), warn: log('warn'), error: log('error') };

  const ensureLive = () => {
    if (lifetime.signal.aborted) throw new Error('Plugin is inactive');
  };
  const dataKey = (key, area) => {
    if (!DATA_KEY.test(String(key))) throw new Error('Invalid plugin data key');
    return `plugin:${pluginId}:${area}:${key}`;
  };
  const disposable = (callback) => {
    let disposed = false;
    const item = { dispose: () => { if (!disposed) { disposed = true; callback(); } } };
    subscriptions.push(item);
    return item;
  };
  const release = (item) => {
    try {
      item.dispose();
    } catch (error) {
      logger.warn('Cleanup failed', error);
    }
  };

  /** Rejects with the signal's reason once it aborts, or when [milliseconds] pass. */
  function withDeadline(work, signals, milliseconds) {
    const controller = new AbortController();
    const abort = (signal) => () => controller.abort(signal.reason ?? new Error('Plugin request cancelled'));
    const listeners = signals.map((signal) => {
      const listener = abort(signal);
      if (signal.aborted) listener();
      else signal.addEventListener('abort', listener, { once: true });
      return [signal, listener];
    });
    const timer = timers.setTimeout(() => controller.abort(new Error('Plugin request timed out')), milliseconds);
    return new Promise((resolve, reject) => {
      const cancelled = () => reject(controller.signal.reason);
      controller.signal.addEventListener('abort', cancelled, { once: true });
      if (controller.signal.aborted) cancelled();
      else Promise.resolve().then(() => work(controller.signal)).then(resolve, reject);
    }).finally(() => {
      timers.clearTimeout(timer);
      listeners.forEach(([signal, listener]) => signal.removeEventListener('abort', listener));
    });
  }

  const configuration = () => JSON.parse(host.configuration());

  async function fetchText(url, options = {}) {
    ensureLive();
    const origin = networkOrigin(String(url));
    if (!origin || !JSON.parse(host.allowedOrigins()).includes(origin)) throw new Error('Network origin is not declared');
    if (options.method && !['GET', 'POST'].includes(options.method)) throw new Error('Unsupported network method');
    if (options.body !== undefined && utf8Length(String(options.body)) > MAX_BODY_BYTES) throw new Error('Network request body exceeds 1 MiB');
    const timeout = options.timeoutMs ?? DEFAULT_FETCH_TIMEOUT_MS;
    if (!Number.isInteger(timeout) || timeout < 1 || timeout > MAX_FETCH_TIMEOUT_MS) throw new Error('Network timeout must be 1-120000 ms');
    const signals = [lifetime.signal, ...(options.signal ? [options.signal] : [])];
    return withDeadline(async (signal) => {
      const response = await fetch(String(url), {
        redirect: 'error',
        signal,
        ...(options.method ? { method: options.method } : {}),
        ...(options.headers ? { headers: options.headers } : {}),
        ...(options.body !== undefined ? { body: String(options.body) } : {}),
      });
      const text = await response.text();
      if (!response.ok) {
        // A short excerpt lets plugins report API errors such as an unknown model or a bad key.
        const detail = text.slice(0, 500).trim();
        throw new Error(`Plugin network HTTP ${response.status}${detail ? `: ${detail}` : ''}`);
      }
      if (utf8Length(text) > MAX_BODY_BYTES) throw new Error('Network response exceeds 1 MiB');
      return text;
    }, signals, timeout);
  }

  function context() {
    return {
      pluginId,
      apiVersions: { coreApi: CORE_API, extensionApis: negotiated(), legacyMusicApi: 1 },
      signal: lifetime.signal,
      subscriptions: new Proxy(subscriptions, {
        get: (items, property, receiver) => (property === 'push'
          ? (...entries) => {
            for (const entry of entries) {
              if (!entry || typeof entry.dispose !== 'function') throw new Error('Invalid plugin subscription');
              if (lifetime.signal.aborted) release(entry);
              else items.push(entry);
            }
            return items.length;
          }
          : Reflect.get(items, property, receiver)),
      }),
      logger,
      extensions: {
        register(type, id, provider) {
          ensureLive();
          if (!Object.hasOwn(negotiated(), type) || !manifest.contributes.providers.some((item) => item.type === type && item.id === id)) {
            throw new Error('Provider is not declared or API is unavailable');
          }
          const key = `${type}:${id}`;
          if (providers.has(key)) throw new Error('Provider is already registered');
          if (type === 'lyrics.translation' && typeof provider?.translate !== 'function') throw new Error('Invalid translation provider');
          if (type === 'music.source') validateMusicSource(provider, pluginId);
          providers.set(key, provider);
          return disposable(() => providers.delete(key));
        },
      },
      storage: {
        get(key) {
          ensureLive();
          const value = host.readSetting(dataKey(key, 'storage'));
          return value == null ? undefined : JSON.parse(value);
        },
        set(key, value) {
          ensureLive();
          const json = JSON.stringify(value);
          if (json === undefined || json.length > MAX_STORAGE_JSON) throw new Error('Plugin storage value exceeds 64 KiB or is not JSON');
          host.writeSetting(dataKey(key, 'storage'), json);
        },
        delete(key) {
          ensureLive();
          host.writeSetting(dataKey(key, 'storage'), null);
        },
      },
      secrets: {
        get(key) {
          ensureLive();
          dataKey(key, 'secret');
          if (manifest.permissions?.secrets !== 'own') throw new Error('Plugin secrets permission is required');
          return host.readSecret(String(key));
        },
        set(key, value) {
          ensureLive();
          dataKey(key, 'secret');
          if (manifest.permissions?.secrets !== 'own' || typeof value !== 'string' || value.length > MAX_SECRET_LENGTH) {
            throw new Error('Invalid plugin secret or missing permission');
          }
          host.writeSecrets(JSON.stringify({ [key]: value }));
        },
      },
      configuration: {
        get(key) {
          ensureLive();
          const values = configuration();
          if (!Object.hasOwn(values, key)) throw new Error('Unknown configuration key');
          return values[key];
        },
        onDidChange(listener) {
          ensureLive();
          configurationListeners.add(listener);
          return disposable(() => configurationListeners.delete(listener));
        },
      },
      cache: {
        async get(key) {
          ensureLive();
          const value = host.cacheGet(String(key));
          return value == null ? undefined : JSON.parse(value);
        },
        async set(key, value) {
          ensureLive();
          const json = JSON.stringify(value);
          if (json === undefined) throw new Error('Plugin cache value is not JSON');
          host.cacheSet(String(key), json);
        },
        async delete(key) {
          ensureLive();
          host.cacheSet(String(key), null);
        },
        async clear() {
          ensureLive();
          host.cacheClear();
        },
      },
      network: { fetchText },
    };
  }

  /** The extension APIs both sides speak; the host checked the manifest's ranges when installing. */
  function negotiated() {
    const versions = {};
    for (const type of Object.keys({ ...manifest.extensionApis, ...(manifest.optionalExtensionApis ?? {}) })) {
      if (EXTENSION_APIS[type]) versions[type] = EXTENSION_APIS[type];
    }
    return versions;
  }

  function define(factory) {
    const module = { exports: {} };
    factory.call(module.exports, module.exports, requireModule, module, `/plugins/${pluginId}/index.cjs`, `/plugins/${pluginId}`);
    moduleExports = module.exports;
  }

  async function activate(manifestJson) {
    manifest = JSON.parse(manifestJson);
    if (typeof moduleExports?.activate !== 'function') throw new Error('Plugin must export activate(context)');
    const result = await moduleExports.activate(context());
    if (result) {
      if (typeof result.dispose !== 'function') throw new Error('activate must return void or a Disposable');
      subscriptions.push(result);
    }
    for (const declaration of manifest.contributes.providers) {
      if (negotiated()[declaration.type] && !providers.has(`${declaration.type}:${declaration.id}`)) {
        throw new Error(`Provider was not registered: ${declaration.id}`);
      }
    }
  }

  async function translate({ requestId, providerId, request, timeoutMs }) {
    ensureLive();
    if (activeCalls >= MAX_CONCURRENT_CALLS) throw new Error('Plugin request concurrency limit reached');
    const provider = providers.get(`lyrics.translation:${providerId}`);
    if (!provider) throw new Error('Translation provider is unavailable');
    const cancel = new AbortController();
    calls.set(requestId, cancel);
    activeCalls++;
    const deadline = Date.now() + timeoutMs;
    try {
      return await withDeadline(
        (signal) => provider.translate(request, { requestId, signal, timeoutMs: Math.max(0, deadline - Date.now()) }),
        [cancel.signal, lifetime.signal],
        timeoutMs,
      );
    } finally {
      activeCalls--;
      calls.delete(requestId);
    }
  }

  function configurationChanged() {
    for (const listener of configurationListeners) {
      try {
        listener();
      } catch (error) {
        logger.warn('Configuration listener failed', error);
      }
    }
  }

  /** The registered music source, as desktop's `PluginRuntime.music()`. */
  function music() {
    ensureLive();
    const entry = manifest?.contributes.providers.find((item) => item.type === 'music.source');
    const provider = entry && providers.get(`music.source:${entry.id}`);
    if (!provider) throw new Error('Music source is not active');
    return provider;
  }

  async function dispatch(operation, payload) {
    if (MUSIC_OPERATIONS.has(operation)) return musicDispatch(music(), operation, payload);
    switch (operation) {
      case 'activate':
        return activate(payload.manifest);
      case 'translate':
        return translate(payload);
      case 'cancel':
        calls.get(payload.requestId)?.abort(new Error('Plugin request cancelled'));
        return null;
      case 'configurationChanged':
        configurationChanged();
        return null;
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
      if (request == null) break;
      const [id, operation, payloadJson] = request;
      Promise.resolve()
        .then(() => dispatch(operation, JSON.parse(payloadJson)))
        .then((value) => respond(id, value), (error) => host.complete(id, false, describe(error)))
        .catch((error) => host.log('error', `Plugin response failed: ${describe(error)}`));
    }
    lifetime.abort(new Error('Plugin is inactive'));
    for (const item of subscriptions.splice(0).reverse()) release(item);
    providers.clear();
    configurationListeners.clear();
    if (typeof moduleExports?.deactivate === 'function') {
      try {
        await moduleExports.deactivate();
      } catch (error) {
        logger.warn('Deactivation failed', error);
      }
    }
  }

  return { define, activate, serve };
}
