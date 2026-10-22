import { Buffer } from 'buffer';

const UTF8_LABELS = new Set(['utf-8', 'utf8', 'unicode-1-1-utf-8']);
const OTHER_LABELS = new Map([
  ['utf-16le', 'utf16le'],
  ['latin1', 'latin1'],
  ['iso-8859-1', 'latin1'],
  ['ascii', 'latin1'],
]);

function bytesOf(input) {
  if (input instanceof ArrayBuffer) return Buffer.from(input);
  if (ArrayBuffer.isView(input)) return Buffer.from(input.buffer, input.byteOffset, input.byteLength);
  throw new TypeError('The "input" argument must be an instance of ArrayBuffer or ArrayBufferView');
}

export class TextEncoder {
  get encoding() {
    return 'utf-8';
  }

  encode(input = '') {
    const bytes = Buffer.from(String(input), 'utf8');
    return new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  }

  encodeInto(source, destination) {
    const bytes = this.encode(source);
    const written = Math.min(bytes.byteLength, destination.byteLength);
    destination.set(bytes.subarray(0, written));
    return { read: String(source).length, written };
  }
}

export class TextDecoder {
  constructor(label = 'utf-8', options = {}) {
    const normalized = String(label).trim().toLowerCase();
    if (UTF8_LABELS.has(normalized)) {
      this._encoding = 'utf8';
      this._name = 'utf-8';
    } else if (OTHER_LABELS.has(normalized)) {
      this._encoding = OTHER_LABELS.get(normalized);
      this._name = normalized;
    } else {
      throw new RangeError(`The "${label}" encoding is not supported`);
    }
    this.fatal = Boolean(options.fatal);
    this.ignoreBOM = Boolean(options.ignoreBOM);
  }

  get encoding() {
    return this._name;
  }

  decode(input) {
    if (input == null) return '';
    const text = bytesOf(input).toString(this._encoding);
    return !this.ignoreBOM && text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  }
}
