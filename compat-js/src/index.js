/**
 * Prelude evaluated in each plugin's QuickJS runtime before the plugin code.
 * It installs the Node and web globals music plugins expect and exposes the
 * runtime controls as `__la` (legacy music plugins) and `__la_platform`
 * (platform plugins). The Kotlin host defines `__la_host` first.
 */
import { Buffer } from 'buffer';
import URL from 'core-js-pure/stable/url/index.js';
import URLSearchParams from 'core-js-pure/stable/url-search-params/index.js';
import { format } from 'util';
import { createAbortApi } from './abort.js';
import { Blob } from './blob.js';
import { createConsole } from './console.js';
import { createCrypto } from './crypto.js';
import { createFetch } from './fetch.js';
import { createLegacyRuntime } from './legacyRuntime.js';
import { createPlatformRuntime } from './platformRuntime.js';
import { createRequire } from './modules.js';
import { processShim } from './process.js';
import { TextDecoder, TextEncoder } from './text.js';
import { createTimers } from './timers.js';

const target = globalThis;
const host = target.__la_host;
if (!host) throw new Error('LyricsAdapter host bindings are not installed');
Reflect.deleteProperty(target, '__la_host');

const console = createConsole((level, message) => host.log(level, message));
const timers = createTimers(host, (error) => console.error('Uncaught error in timer callback:', error));
const abortApi = createAbortApi(timers);
const crypto = createCrypto(host);
const { fetch, Headers, Response } = createFetch(host, abortApi);

Object.assign(target, {
  global: target,
  process: processShim,
  Buffer,
  URL,
  URLSearchParams,
  TextEncoder,
  TextDecoder,
  Blob,
  console,
  fetch,
  Headers,
  Response,
  AbortController: abortApi.AbortController,
  AbortSignal: abortApi.AbortSignal,
  DOMException: abortApi.DOMException,
  Event: abortApi.Event,
  EventTarget: abortApi.EventTarget,
  crypto: { getRandomValues: crypto.getRandomValues, randomUUID: crypto.randomUUID },
  queueMicrotask: (callback) => {
    Promise.resolve().then(callback).catch((error) => console.error('Uncaught error in microtask:', error));
  },
  atob: (data) => Buffer.from(String(data), 'base64').toString('latin1'),
  btoa: (data) => Buffer.from(String(data), 'latin1').toString('base64'),
  ...timers,
});

const requireModule = createRequire({ crypto, timers, TextEncoder, TextDecoder });
target.__la = createLegacyRuntime({ pluginId: host.pluginId(), host, requireModule, format });
// Plugins on desktop's platform API (`activate(context)`); the host picks one runtime per plugin.
target.__la_platform = createPlatformRuntime({ pluginId: host.pluginId(), host, requireModule, format, abortApi, timers });
