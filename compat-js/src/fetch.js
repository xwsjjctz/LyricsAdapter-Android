import { Buffer } from 'buffer';
import { Blob, copyToArrayBuffer } from './blob.js';

/**
 * WHATWG fetch with Node (undici) semantics where plugins depend on them:
 * no CORS, any request header, real 3xx responses for redirect: 'manual', and
 * Headers#getSetCookie(). The host performs the request and the redirects.
 */
const TOKEN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;
const NORMALIZED_METHODS = new Set(['DELETE', 'GET', 'HEAD', 'OPTIONS', 'POST', 'PUT', 'PATCH']);
const REDIRECT_MODES = new Set(['follow', 'manual', 'error']);

function headerName(name) {
  const value = String(name);
  if (!TOKEN.test(value)) throw new TypeError(`Invalid header name: "${value}"`);
  return value.toLowerCase();
}

function headerValue(value) {
  const text = String(value).replace(/^[\t\n\r ]+|[\t\n\r ]+$/g, '');
  if (/[\0\r\n]/.test(text)) throw new TypeError('Invalid header value');
  return text;
}

export class Headers {
  constructor(init) {
    this._entries = [];
    if (init == null) return;
    if (init instanceof Headers) {
      this._entries = init._entries.map(([name, value]) => [name, value]);
    } else if (typeof init[Symbol.iterator] === 'function') {
      for (const pair of init) {
        const [name, value] = Array.from(pair);
        this.append(name, value);
      }
    } else {
      for (const name of Object.keys(init)) this.append(name, init[name]);
    }
  }

  append(name, value) {
    this._entries.push([headerName(name), headerValue(value)]);
  }

  set(name, value) {
    const key = headerName(name);
    this._entries = [...this._entries.filter(([entry]) => entry !== key), [key, headerValue(value)]];
  }

  delete(name) {
    const key = headerName(name);
    this._entries = this._entries.filter(([entry]) => entry !== key);
  }

  has(name) {
    const key = headerName(name);
    return this._entries.some(([entry]) => entry === key);
  }

  get(name) {
    const key = headerName(name);
    const values = this._entries.filter(([entry]) => entry === key).map(([, value]) => value);
    return values.length > 0 ? values.join(', ') : null;
  }

  getSetCookie() {
    return this._entries.filter(([entry]) => entry === 'set-cookie').map(([, value]) => value);
  }

  *entries() {
    const names = [...new Set(this._entries.map(([name]) => name))].sort();
    for (const name of names) {
      if (name === 'set-cookie') {
        for (const value of this.getSetCookie()) yield [name, value];
      } else {
        yield [name, this.get(name)];
      }
    }
  }

  *keys() {
    for (const [name] of this.entries()) yield name;
  }

  *values() {
    for (const [, value] of this.entries()) yield value;
  }

  forEach(callback, thisArg) {
    for (const [name, value] of this.entries()) callback.call(thisArg, value, name, this);
  }

  [Symbol.iterator]() {
    return this.entries();
  }
}

function bodyBytes(body) {
  if (body == null) return null;
  if (Buffer.isBuffer(body)) return body;
  if (typeof body === 'string') return Buffer.from(body, 'utf8');
  if (body instanceof Blob) return body._bytes;
  if (body instanceof ArrayBuffer) return Buffer.from(body);
  if (ArrayBuffer.isView(body)) return Buffer.from(body.buffer, body.byteOffset, body.byteLength);
  if (body instanceof URLSearchParams) return Buffer.from(body.toString(), 'utf8');
  throw new TypeError('Unsupported body type');
}

export class Response {
  constructor(body = null, init = {}) {
    this._body = bodyBytes(body);
    this.status = init.status ?? 200;
    this.statusText = init.statusText ?? '';
    this.headers = new Headers(init.headers);
    this.url = '';
    this.redirected = false;
    this.type = 'default';
    this.bodyUsed = false;
  }

  get ok() {
    return this.status >= 200 && this.status <= 299;
  }

  _consume() {
    if (this.bodyUsed) throw new TypeError('Body is unusable: Body has already been read');
    this.bodyUsed = true;
    return this._body ?? Buffer.alloc(0);
  }

  async arrayBuffer() {
    return copyToArrayBuffer(this._consume());
  }

  async bytes() {
    return new Uint8Array(copyToArrayBuffer(this._consume()));
  }

  async text() {
    const text = this._consume().toString('utf8');
    return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  }

  async json() {
    return JSON.parse(await this.text());
  }

  async blob() {
    return new Blob([this._consume()], { type: this.headers.get('content-type') ?? '' });
  }

  clone() {
    if (this.bodyUsed) throw new TypeError('Response.clone: Body has already been consumed.');
    const copy = new Response(this._body ? Buffer.from(this._body) : null, this);
    copy.url = this.url;
    copy.redirected = this.redirected;
    copy.type = this.type;
    return copy;
  }

  static json(data, init = {}) {
    const headers = new Headers(init.headers);
    if (!headers.has('content-type')) headers.set('content-type', 'application/json');
    return new Response(JSON.stringify(data), { ...init, headers });
  }

  static error() {
    const response = new Response(null, { status: 0 });
    response.type = 'error';
    return response;
  }
}

/** Encodes a request body, setting the content type fetch would infer. */
function requestBody(body, headers) {
  if (body == null) return null;
  const defaultType = typeof body === 'string'
    ? 'text/plain;charset=UTF-8'
    : body instanceof URLSearchParams
      ? 'application/x-www-form-urlencoded;charset=UTF-8'
      : body instanceof Blob && body.type ? body.type : null;
  if (defaultType && !headers.has('content-type')) headers.set('content-type', defaultType);
  return bodyBytes(body);
}

function requestUrl(input) {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.href;
  if (input && typeof input.url === 'string') return input.url;
  return String(input);
}

export function createFetch(host, { abortError }) {
  const toHost = (buffer) => new Int8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  const fromHost = (bytes) => Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let nextRequestId = 1;

  async function fetch(input, init = {}) {
    const url = requestUrl(input);
    const requestedMethod = String(init.method ?? input?.method ?? 'GET');
    const method = NORMALIZED_METHODS.has(requestedMethod.toUpperCase()) ? requestedMethod.toUpperCase() : requestedMethod;
    const headers = new Headers(init.headers ?? input?.headers);
    const redirect = init.redirect ?? 'follow';
    if (!REDIRECT_MODES.has(redirect)) throw new TypeError(`Invalid redirect mode: ${redirect}`);
    const signal = init.signal ?? null;
    if (signal?.aborted) throw signal.reason ?? abortError();
    const body = requestBody(init.body, headers);
    if (body && (method === 'GET' || method === 'HEAD')) throw new TypeError('Request with GET/HEAD method cannot have body.');

    const requestId = nextRequestId++;
    const onAbort = () => host.cancelFetch(requestId);
    signal?.addEventListener?.('abort', onAbort, { once: true });
    let exchange;
    try {
      const meta = JSON.stringify({ url, method, headers: headers._entries, redirect });
      exchange = await host.fetch(meta, body ? toHost(body) : null, requestId);
    } catch (error) {
      if (signal?.aborted) throw signal.reason ?? abortError();
      const failure = new TypeError(`fetch failed: ${error?.message ?? error}`);
      failure.cause = error;
      throw failure;
    } finally {
      signal?.removeEventListener?.('abort', onAbort);
    }

    const [metaJson, responseBody] = exchange;
    const meta = JSON.parse(metaJson);
    const response = new Response(responseBody ? fromHost(responseBody) : null, meta);
    response.url = meta.url;
    response.redirected = meta.redirected;
    response.type = 'basic';
    return response;
  }

  return { fetch, Headers, Response };
}
