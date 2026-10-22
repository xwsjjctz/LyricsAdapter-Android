import assert from 'assert';
import buffer from 'buffer';
import events from 'events';
import stream from 'stream';
import stringDecoder from 'string_decoder';
import util from 'util';
import zlib from 'zlib';
import { fsModule } from './fs.js';
import { processShim } from './process.js';

/** `require` for the Node built-ins plugin bundles leave external, with or without the `node:` prefix. */
export function createRequire({ crypto, timers, TextEncoder, TextDecoder }) {
  const modules = new Map();
  const register = (name, value) => {
    modules.set(name, value);
    modules.set(`node:${name}`, value);
  };

  register('assert', assert);
  register('buffer', buffer);
  register('crypto', crypto);
  register('events', events);
  register('fs', fsModule);
  register('process', processShim);
  register('stream', stream);
  register('string_decoder', stringDecoder);
  register('timers', timers);
  register('url', { URL: globalThis.URL, URLSearchParams: globalThis.URLSearchParams });
  register('util', { ...util, TextEncoder, TextDecoder });
  register('zlib', zlib);

  return function requireModule(name) {
    const key = String(name);
    if (modules.has(key)) return modules.get(key);
    const error = new Error(`Cannot find module '${key}'`);
    error.code = 'MODULE_NOT_FOUND';
    throw error;
  };
}
