// Prints what the desktop app reads from the given files, e.g. files written by
// the Android tag writer: node read.mjs <file>...
import { readLikeDesktop } from './desktop.mjs';

const results = {};
for (const file of process.argv.slice(2)) results[file] = await readLikeDesktop(file);
console.log(JSON.stringify(results, null, 2));
