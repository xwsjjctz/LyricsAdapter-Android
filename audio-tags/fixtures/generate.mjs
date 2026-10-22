// Builds the fixtures in files/ with the desktop's tag libraries and records in
// expected.json what desktop reads back from each. The Kotlin tests compare the
// Android reader against expected.json. Requires ffmpeg and metaflac.
import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLikeDesktop, writeCommonFields, writeFlacField, writeMp3Private, writeMp3UserText } from './desktop.mjs';

/** Kept in sync with AudioTagsTest: data the tag writer must leave in place. */
const UNMAPPED_MARKER = 'unmapped data to keep';

const root = path.dirname(fileURLToPath(import.meta.url));
const files = path.join(root, 'files');
rmSync(files, { recursive: true, force: true });
mkdirSync(files, { recursive: true });

const ffmpeg = (...args) => execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args]);
const tone = ['-f', 'lavfi', '-i', 'sine=frequency=440:duration=2', '-ac', '2', '-ar', '44100', '-map_metadata', '-1', '-fflags', '+bitexact'];
const encoders = {
  mp3: ['-c:a', 'libmp3lame', '-b:a', '128k', '-id3v2_version', '0', '-write_id3v1', '0'],
  flac: ['-c:a', 'flac'],
  m4a: ['-c:a', 'aac', '-b:a', '128k'],
};
const audio = (name) => {
  const output = path.join(files, name);
  ffmpeg(...tone, ...encoders[path.extname(name).slice(1)], output);
  return output;
};

/** Appends an ID3v1.1 tag: "TAG" and Latin-1 fields NUL-padded to fixed widths, 128 bytes. */
function appendId3v1(file, { title, artist, album }) {
  const field = (text, width) => {
    const bytes = Buffer.alloc(width);
    bytes.write(text, 'latin1');
    return bytes;
  };
  const year = field('', 4);
  const comment = field('', 28);
  const trackAndGenre = Buffer.from([0, 0, 255]);
  appendFileSync(file, Buffer.concat([Buffer.from('TAG', 'latin1'), field(title, 30), field(artist, 30), field(album, 30), year, comment, trackAndGenre]));
}

ffmpeg('-f', 'lavfi', '-i', 'color=c=0xff5fa2:s=64x64', '-frames:v', '1', path.join(files, 'cover.png'));
ffmpeg('-f', 'lavfi', '-i', 'color=c=0x5f8bff:s=64x64', '-frames:v', '1', path.join(files, 'cover.jpg'));
const png = { mimeType: 'image/png', data: readFileSync(path.join(files, 'cover.png')) };
const jpg = { mimeType: 'image/jpeg', data: readFileSync(path.join(files, 'cover.jpg')) };

const lrc = '[ti:晴天]\n[ar:周杰伦]\n[00:00.00]故事的小黄花\n[00:01.00]从出生那年就飘着\n';
// Decrypted QRC as QQ Music serves it; the apostrophe is the case desktop's parser guards against.
const qrc = [
  '<?xml version="1.0" encoding="utf-8"?>',
  '<QrcInfos>',
  '<QrcHeadInfo SaveTime="1" Version="100"/>',
  '<LyricInfo LyricCount="1">',
  '<Lyric_1 LyricType="1" LyricContent="[ti:晴天]',
  '[0,1000]故(0,250)事(250,250)的(500,250)小(750,250)',
  "[1000,1000]Don't(1000,500) (1500,0)stop(1500,500)",
  '"/>',
  '</LyricInfo>',
  '</QrcInfos>',
].join('\n');
const yrc = '[0,1000](0,250,0)故(250,250,0)事(500,250,0)的(750,250,0)小\n[1000,1000](1000,500,0)从(1500,500,0)出\n';

const cases = {
  // ID3v2 written by music-tag-native, then rewritten by node-id3 for TXXX:QRC, as desktop does.
  // The PRIV frame stands for data lofty cannot map, which edits must keep.
  'tagged.mp3': async (file) => {
    await writeCommonFields(file, { title: '晴天', artist: '周杰伦', album: '叶惠美', lyrics: lrc, cover: jpg });
    writeMp3UserText(file, 'QRC', qrc);
    writeMp3Private(file, 'lyricsadapter-fixture', UNMAPPED_MARKER);
  },
  // Both word-lyric formats: QRC must win. LA_FIXTURE is a field lofty cannot map.
  'tagged.flac': async (file) => {
    await writeCommonFields(file, { title: '晴天', artist: '周杰伦', album: '叶惠美', lyrics: lrc, cover: png });
    writeFlacField(file, 'YRC', yrc);
    writeFlacField(file, 'QRC', qrc);
    writeFlacField(file, 'LA_FIXTURE', UNMAPPED_MARKER);
  },
  // Field names match case-insensitively.
  'lowercase-yrc.flac': async (file) => {
    await writeCommonFields(file, { title: '稻香' });
    writeFlacField(file, 'yrc', yrc);
  },
  'untagged.mp3': async () => {},
  // ID3v1 only, as in old rips; ID3v1 holds Latin-1 text only.
  'id3v1-only.mp3': async (file) => {
    appendId3v1(file, { title: 'Old Rip', artist: 'Some Artist', album: 'Old Album' });
  },
  // MP4 has a lyrics field but no word-lyric storage.
  'tagged.m4a': async (file) => {
    await writeCommonFields(file, { title: '晴天', artist: '周杰伦', album: '叶惠美', lyrics: lrc, cover: jpg });
  },
};

const expected = {};
for (const [name, tag] of Object.entries(cases)) {
  const file = audio(name);
  await tag(file);
  expected[name] = await readLikeDesktop(file);
}
writeFileSync(path.join(root, 'expected.json'), `${JSON.stringify(expected, null, 2)}\n`);
console.log(`Wrote ${Object.keys(expected).length} fixtures and expected.json`);
