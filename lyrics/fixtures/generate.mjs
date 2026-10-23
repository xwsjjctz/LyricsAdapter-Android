// Records in expected.json what the desktop app's lyrics parser returns for each
// case below. The Kotlin parser is tested against it, so desktop stays the
// reference without its AGPL parser (AMLL) being ported.
//
//   node lyrics/fixtures/generate.mjs [path to the LyricsAdapter desktop checkout]
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const desktop = path.resolve(process.argv[2] ?? path.join(here, '../../../LyricsAdapter'));
const { parseLyrics } = await import(pathToFileURL(path.join(desktop, 'src/shared/lrcParser.ts')).href);

const qrcXml = (content) =>
  `<?xml version="1.0" encoding="utf-8"?>\n<QrcInfos>\n<QrcHeadInfo SaveTime="1" Version="100"/>\n<LyricInfo LyricCount="1">\n<Lyric_1 LyricType="1" LyricContent="${content}"/>\n</LyricInfo>\n</QrcInfos>`;

const cases = [
  { name: 'lrc basic', lyrics: '[00:01.00]第一行\n[00:02.50]第二行' },
  { name: 'lrc three-digit fraction', lyrics: '[00:01.123]a\n[00:02.5]b\n[00:03.05]c' },
  { name: 'lrc without fraction', lyrics: '[00:01]a\n[01:02]b' },
  { name: 'lrc colon fraction', lyrics: '[00:01:50]a\n[00:02:00]b' },
  { name: 'lrc repeated timestamps', lyrics: '[00:01.00][00:05.00]副歌\n[00:03.00]主歌' },
  { name: 'lrc unsorted', lyrics: '[00:05.00]后\n[00:01.00]前\n[00:03.00]中' },
  { name: 'lrc header tags', lyrics: '[ti:晴天]\n[ar:周杰伦]\n[al:叶惠美]\n[by:someone]\n[offset:500]\n[00:01.00]歌词' },
  { name: 'lrc empty lines and gaps', lyrics: '[00:01.00]一\n\n[00:02.00]\n[00:03.00]   \n[00:04.00]二' },
  { name: 'lrc crlf', lyrics: '[00:01.00]一\r\n[00:02.00]二\r\n' },
  { name: 'lrc surrounding spaces', lyrics: '[00:01.00]  hello world  \n[00:02.00]\tTabbed' },
  { name: 'lrc long minutes', lyrics: '[61:00.00]long\n[120:30.50]longer' },
  { name: 'lrc text before first timestamp', lyrics: 'untimed line\n[00:01.00]timed' },
  { name: 'lrc brackets in text', lyrics: '[00:01.00]hello [world]\n[00:02.00](笑)' },
  { name: 'lrc parentheses', lyrics: '[00:01.00](hello) world\n[00:02.00]hello (world)\n[00:03.00]（全角）\n[00:04.00]()\n[00:05.00]( spaced )\n[00:06.00]((double))' },
  { name: 'lrc fraction digits', lyrics: '[00:01.1234]four\n[00:02.999]nines\n[00:03.]dot' },
  { name: 'plain text', lyrics: '第一行\n第二行\n\n第三行' },
  { name: 'plain text with header tags', lyrics: '[ti:标题]\n[ar:歌手]\n没有时间轴的歌词' },
  { name: 'empty', lyrics: '' },
  {
    name: 'qrc basic',
    lyrics: '[00:00.00]fallback',
    wordLyrics: qrcXml('[ti:晴天]\n[1000,1000]故(1000,250)事(1250,250)的(1500,250)小(1750,250)\n[2000,800]黄(2000,400)花(2400,400)\n'),
    format: 'qrc',
  },
  {
    name: 'qrc english separators',
    lyrics: '',
    wordLyrics: qrcXml("[0,2000]Don't(0,500) (500,0)stop(500,500) (1000,0)me(1000,1000)\n"),
    format: 'qrc',
  },
  {
    name: 'qrc leading separator',
    lyrics: '',
    wordLyrics: qrcXml(' (0,0)Hi(0,500) (500,0)there(500,500)\n'),
    format: 'qrc',
  },
  {
    name: 'qrc xml entities',
    lyrics: '',
    wordLyrics: qrcXml('[0,1000]&quot;Rock(0,500)&amp;Roll&quot;(500,500)\n[1000,500]&lt;3(1000,500)\n'),
    format: 'qrc',
  },
  {
    name: 'qrc raw content',
    lyrics: '',
    wordLyrics: '[0,1000]无(0,500)壳(500,500)\n',
    format: 'qrc',
  },
  {
    name: 'qrc invalid falls back to lrc',
    lyrics: '[00:01.00]回退的歌词',
    wordLyrics: 'not a qrc document',
    format: 'qrc',
  },
  {
    name: 'yrc basic',
    lyrics: '',
    wordLyrics: '[1000,1000](1000,500,0)逐(1500,500,0)字\n[2000,1000](2000,1000,0)第二\n',
    format: 'yrc',
  },
  {
    name: 'yrc with json credits',
    lyrics: '',
    wordLyrics: '{"t":0,"c":[{"tx":"作词: "},{"tx":"某人"}]}\n{"t":500,"c":[{"tx":"作曲: "},{"tx":"某人"}]}\n[1000,1000](1000,500,0)逐(1500,500,0)字\n',
    format: 'yrc',
  },
  {
    name: 'yrc english',
    lyrics: '',
    wordLyrics: '[0,1500](0,500,0)Hello (500,500,0)my (1000,500,0)friend\n',
    format: 'yrc',
  },
  {
    name: 'yrc invalid falls back to lrc',
    lyrics: '[00:02.00]备用',
    wordLyrics: '{"t":0,"c":[{"tx":"only credits"}]}\n',
    format: 'yrc',
  },
];

const expected = cases.map(({ name, lyrics, wordLyrics, format }) => ({
  name,
  lyrics,
  wordLyrics: wordLyrics ?? null,
  format: format ?? null,
  result: parseLyrics(lyrics, wordLyrics, format),
}));
writeFileSync(path.join(here, 'expected.json'), `${JSON.stringify(expected, null, 1)}\n`);
console.log(`Wrote ${expected.length} cases`);
