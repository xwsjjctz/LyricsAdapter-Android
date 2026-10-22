import { Buffer } from 'buffer';

/**
 * The subset of node:crypto that music plugins use, backed by the platform's
 * crypto through host bindings. Ciphers buffer their input and transform it in
 * final(), which still yields the right result for update()+final() callers.
 */
const MAX_RANDOM_BYTES = 65536;

function toBuffer(data, encoding) {
  if (typeof data === 'string') return Buffer.from(data, encoding && encoding !== 'buffer' ? encoding : 'utf8');
  if (Buffer.isBuffer(data)) return data;
  if (ArrayBuffer.isView(data)) return Buffer.from(data.buffer, data.byteOffset, data.byteLength);
  if (data instanceof ArrayBuffer) return Buffer.from(data);
  throw new TypeError('The "data" argument must be of type string or an instance of Buffer, TypedArray, or DataView');
}

const encode = (buffer, encoding) => (encoding && encoding !== 'buffer' ? buffer.toString(encoding) : buffer);

export function createCrypto(host) {
  const toHost = (buffer) => new Int8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  const fromHost = (bytes) => Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);

  function randomBytes(size, callback) {
    if (!Number.isSafeInteger(size) || size < 0) throw new RangeError('The "size" argument is out of range');
    const chunks = [];
    for (let remaining = size; remaining > 0; remaining -= MAX_RANDOM_BYTES) {
      chunks.push(fromHost(host.randomBytes(Math.min(remaining, MAX_RANDOM_BYTES))));
    }
    const bytes = Buffer.concat(chunks, size);
    if (typeof callback !== 'function') return bytes;
    Promise.resolve().then(() => callback(null, bytes));
    return undefined;
  }

  function randomFillSync(target, offset = 0, size) {
    const view = ArrayBuffer.isView(target)
      ? new Uint8Array(target.buffer, target.byteOffset, target.byteLength)
      : new Uint8Array(target);
    view.set(randomBytes(size ?? view.byteLength - offset), offset);
    return target;
  }

  function getRandomValues(typedArray) {
    if (!ArrayBuffer.isView(typedArray)) throw new TypeError('The "typedArray" argument must be an ArrayBufferView');
    if (typedArray.byteLength > MAX_RANDOM_BYTES) throw new RangeError('The ArrayBufferView byte length exceeds 65536');
    return randomFillSync(typedArray);
  }

  function randomInt(min, max, callback) {
    const [low, high] = max === undefined || typeof max === 'function' ? [0, min] : [min, max];
    const done = typeof max === 'function' ? max : callback;
    if (!Number.isSafeInteger(low) || !Number.isSafeInteger(high) || high <= low) throw new RangeError('The value of "max" is out of range');
    const range = high - low;
    const limit = 2 ** 48 - (2 ** 48 % range);
    let value;
    do value = randomBytes(6).readUIntBE(0, 6); while (value >= limit);
    const result = low + (value % range);
    if (typeof done !== 'function') return result;
    Promise.resolve().then(() => done(null, result));
    return undefined;
  }

  class Hash {
    constructor(algorithm, key) {
      this._algorithm = String(algorithm).toLowerCase();
      this._key = key;
      this._chunks = [];
      this._finished = false;
    }

    update(data, encoding) {
      if (this._finished) throw new Error('Digest already called');
      this._chunks.push(toBuffer(data, encoding));
      return this;
    }

    digest(encoding) {
      if (this._finished) throw new Error('Digest already called');
      this._finished = true;
      const data = toHost(Buffer.concat(this._chunks));
      const result = this._key
        ? host.hmac(this._algorithm, toHost(this._key), data)
        : host.digest(this._algorithm, data);
      return encode(fromHost(result), encoding);
    }

    copy() {
      const copy = new Hash(this._algorithm, this._key);
      copy._chunks = [...this._chunks];
      return copy;
    }
  }

  class Cipher {
    constructor(algorithm, key, iv, encrypt) {
      this._algorithm = String(algorithm).toLowerCase();
      this._key = toBuffer(key);
      this._iv = iv == null ? null : toBuffer(iv);
      this._encrypt = encrypt;
      this._autoPadding = true;
      this._chunks = [];
      this._finished = false;
    }

    update(data, inputEncoding, outputEncoding) {
      if (this._finished) throw new Error('Unsupported state');
      this._chunks.push(toBuffer(data, inputEncoding));
      return encode(Buffer.alloc(0), outputEncoding);
    }

    final(outputEncoding) {
      if (this._finished) throw new Error('Unsupported state');
      this._finished = true;
      const iv = this._iv && this._iv.byteLength > 0 ? toHost(this._iv) : null;
      const input = toHost(Buffer.concat(this._chunks));
      const output = host.cipher(this._algorithm, this._encrypt, toHost(this._key), iv, input, this._autoPadding);
      return encode(fromHost(output), outputEncoding);
    }

    setAutoPadding(autoPadding = true) {
      this._autoPadding = Boolean(autoPadding);
      return this;
    }
  }

  function timingSafeEqual(left, right) {
    const a = toBuffer(left);
    const b = toBuffer(right);
    if (a.byteLength !== b.byteLength) throw new RangeError('Input buffers must have the same byte length');
    let difference = 0;
    for (let index = 0; index < a.byteLength; index++) difference |= a[index] ^ b[index];
    return difference === 0;
  }

  return {
    randomBytes,
    randomFillSync,
    randomInt,
    randomUUID: () => host.randomUUID(),
    getRandomValues,
    createHash: (algorithm) => new Hash(algorithm, null),
    createHmac: (algorithm, key) => new Hash(algorithm, toBuffer(key)),
    createCipheriv: (algorithm, key, iv) => new Cipher(algorithm, key, iv, true),
    createDecipheriv: (algorithm, key, iv) => new Cipher(algorithm, key, iv, false),
    timingSafeEqual,
    getHashes: () => ['md5', 'sha1', 'sha224', 'sha256', 'sha384', 'sha512'],
    getCiphers: () => ['aes-128-cbc', 'aes-192-cbc', 'aes-256-cbc', 'aes-128-ecb', 'aes-192-ecb', 'aes-256-ecb', 'aes-128-ctr', 'aes-192-ctr', 'aes-256-ctr'],
    webcrypto: { getRandomValues, randomUUID: () => host.randomUUID() },
  };
}
