import { Buffer } from 'buffer';

function bytesOf(part) {
  if (part instanceof Blob) return part._bytes;
  if (typeof part === 'string') return Buffer.from(part, 'utf8');
  if (part instanceof ArrayBuffer) return Buffer.from(part);
  if (ArrayBuffer.isView(part)) return Buffer.from(part.buffer, part.byteOffset, part.byteLength);
  return Buffer.from(String(part), 'utf8');
}

export function copyToArrayBuffer(bytes) {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}

export class Blob {
  constructor(parts = [], options = {}) {
    this._bytes = Buffer.concat(Array.from(parts, bytesOf));
    this.type = options.type ? String(options.type).toLowerCase() : '';
  }

  get size() {
    return this._bytes.byteLength;
  }

  async arrayBuffer() {
    return copyToArrayBuffer(this._bytes);
  }

  async bytes() {
    return new Uint8Array(copyToArrayBuffer(this._bytes));
  }

  async text() {
    return this._bytes.toString('utf8');
  }

  slice(start, end, type) {
    return new Blob([this._bytes.subarray(start, end)], { type });
  }
}
