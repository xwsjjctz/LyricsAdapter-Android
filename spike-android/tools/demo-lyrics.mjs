// Writes src/main/assets/demo-lyrics.json for the lyrics rendering spike: word-
// timed lines in QQ's QRC layout, parsed by the desktop app's own lrcParser.ts
// so the app renders exactly the shape desktop produces. The text is original.
//
//   node tools/demo-lyrics.mjs [path to the LyricsAdapter desktop checkout]
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const desktop = path.resolve(process.argv[2] ?? path.join(here, '../../../LyricsAdapter'));
const { parseLyrics } = await import(pathToFileURL(path.join(desktop, 'src/shared/lrcParser.ts')).href);

const lines = [
  '夜色慢慢落在窗台上',
  '街灯把影子拉得很长',
  'Every little light keeps on shining',
  '我数着雨滴落下的声响',
  '风吹过空荡荡的走廊',
  'Hold the moment while it is glowing',
  '旧唱片还在转个不停',
  '你说过的话还很清晰',
  'We were dancing in the quiet rain',
  '把心事折成纸飞机',
  '飞过那片安静的海',
  'Every echo calls your name again',
  '时间像潮水退去又回来',
  '留下贝壳和一点点期待',
  'Turn the radio up and sing along',
  '天亮以前别说再见',
  '让我们再多走一段',
  'All the stars are falling into place',
  '夜色慢慢落在窗台上',
  '街灯把影子拉得很长',
  'Every little light keeps on shining',
  '我会记得这一刻的光',
];

/** Chinese lines time each character; English lines time words, with QQ's zero-length space tokens. */
function qrcLine(text, start) {
  const tokens = /[一-鿿]/.test(text)
    ? [...text].map((char) => ({ text: char, duration: 260 + (char.codePointAt(0) % 5) * 40 }))
    : text.split(' ').flatMap((word, index) => [
      ...(index > 0 ? [{ text: ' ', duration: 0 }] : []),
      { text: word, duration: 200 + word.length * 45 },
    ]);
  let cursor = start;
  const words = tokens.map((token) => {
    const word = `${token.text}(${cursor},${token.duration})`;
    cursor += token.duration;
    return word;
  });
  return { text: `[${start},${cursor - start}]${words.join('')}`, end: cursor };
}

let start = 1500;
const body = lines.map((text) => {
  const line = qrcLine(text, start);
  start = line.end + 900;
  return line.text;
});
const qrc = `<?xml version="1.0" encoding="utf-8"?>\n<QrcInfos>\n<LyricInfo LyricCount="1">\n<Lyric_1 LyricType="1" LyricContent="${body.join('\n')}\n"/>\n</LyricInfo>\n</QrcInfos>`;

const parsed = parseLyrics('', qrc, 'qrc');
const output = path.join(here, '../src/main/assets/demo-lyrics.json');
writeFileSync(output, `${JSON.stringify({ durationSeconds: start / 1000, ...parsed }, null, 1)}\n`);
console.log(`${parsed.syncedLyrics.length} lines, ${(start / 1000).toFixed(1)} s → ${output}`);
