import { build } from 'esbuild';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const outfile = path.join(root, '../plugin-host/src/main/resources/com/lyricsadapter/pluginhost/compat.js');

await build({
  entryPoints: [path.join(root, 'src/index.js')],
  outfile,
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: 'es2020',
  minify: process.argv.includes('--minify'),
  legalComments: 'eof',
  // Node built-ins the plugins and the polyfills require by bare name.
  alias: {
    stream: 'stream-browserify',
    zlib: 'browserify-zlib',
  },
  // Polyfills reference these as free globals.
  inject: [path.join(root, 'src/inject.js')],
  define: { global: 'globalThis' },
  logLevel: 'info',
});
