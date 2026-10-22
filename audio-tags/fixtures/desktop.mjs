// How the desktop app (LyricsAdapter repository) reads and writes tags:
// music-tag-native for the common fields (electron/services/audioMetadataService.ts),
// node-id3 TXXX frames for MP3 word lyrics and a Vorbis comment field for FLAC
// (electron/services/wordLyricsTagService.ts, flacVorbisWriter.ts). metaflac
// stands in for flacVorbisWriter; both store `KEY=value` comments.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { MetaPicture, MusicFile } from 'music-tag-native';
import NodeID3 from 'node-id3';

export async function writeCommonFields(filePath, { title, artist, album, lyrics, cover }) {
  const file = await MusicFile.load(path.resolve(filePath));
  if (title !== undefined) file.title = title;
  if (artist !== undefined) file.artist = artist;
  if (album !== undefined) file.album = album;
  if (lyrics !== undefined) file.lyrics = lyrics;
  if (cover !== undefined) file.pictures = [new MetaPicture(cover.mimeType, new Uint8Array(cover.data))];
  await file.save();
}

/** Mirrors wordLyricsTagService's upsertMp3UserDefinedText. */
export function writeMp3UserText(filePath, description, value) {
  const existing = NodeID3.read(filePath);
  const { raw: _raw, ...tags } = existing;
  const kept = (existing.userDefinedText ?? [])
    .filter((entry) => entry?.description && entry.description.toUpperCase() !== description)
    .map((entry) => ({ description: entry.description, value: entry.value ?? '' }));
  if (value !== undefined) kept.push({ description, value });
  if (kept.length > 0) tags.userDefinedText = kept;
  else delete tags.userDefinedText;
  const result = NodeID3.write(tags, filePath);
  if (result instanceof Error) throw result;
}

/** A PRIV frame, which lofty does not map to a generic item. */
export function writeMp3Private(filePath, ownerIdentifier, text) {
  const result = NodeID3.update({ private: [{ ownerIdentifier, data: Buffer.from(text, 'utf8') }] }, filePath);
  if (result instanceof Error) throw result;
}

export function writeFlacField(filePath, key, value) {
  const directory = mkdtempSync(path.join(tmpdir(), 'la-fixture-'));
  try {
    const valueFile = path.join(directory, 'value.txt');
    writeFileSync(valueFile, value, 'utf8');
    execFileSync('metaflac', [`--remove-tag=${key}`, `--set-tag-from-file=${key}=${valueFile}`, filePath]);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

/** What desktop's readAudioMetadata and readWordLyrics see, before lyric parsing. */
export async function readLikeDesktop(filePath) {
  const file = await MusicFile.load(path.resolve(filePath));
  const picture = file.pictures?.[0];
  return {
    title: file.title ?? null,
    artist: file.artist ?? null,
    album: file.album ?? null,
    lyrics: file.lyrics ?? null,
    wordLyrics: readWordLyrics(filePath),
    durationMs: Math.floor(file.duration),
    bitRate: file.bitRate ?? null,
    sampleRate: file.sampleRate ?? null,
    cover: picture
      ? { mimeType: picture.mimeType || 'image/jpeg', size: picture.data.length, sha256: sha256(picture.data) }
      : null,
  };
}

function readWordLyrics(filePath) {
  const field = filePath.endsWith('.flac')
    ? (key) => flacField(filePath, key)
    : filePath.endsWith('.mp3') ? (key) => mp3UserText(filePath)[key] : () => undefined;
  const qrc = field('QRC');
  if (qrc) return { format: 'qrc', text: qrc };
  const yrc = field('YRC');
  if (yrc) return { format: 'yrc', text: yrc };
  return null;
}

function mp3UserText(filePath) {
  const result = {};
  for (const entry of NodeID3.read(filePath)?.userDefinedText ?? []) {
    if (entry?.description) result[entry.description.toUpperCase()] = entry.value ?? '';
  }
  return result;
}

/** metaflac matches field names case-insensitively and prints `name=value`; values may span lines. */
function flacField(filePath, key) {
  const shown = execFileSync('metaflac', ['--no-utf8-convert', `--show-tag=${key}`, filePath]).toString('utf8');
  if (!shown) return undefined;
  return shown.slice(shown.indexOf('=') + 1).replace(/\n$/, '');
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}
