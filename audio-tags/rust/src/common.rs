//! The common fields, through lofty's generic tag with music-tag-native's semantics.

use std::fs::File;
use std::io::{BufReader, Seek};
use std::time::Duration;

use lofty::config::WriteOptions;
use lofty::file::TaggedFile;
use lofty::picture::{MimeType, Picture, PictureType};
use lofty::prelude::*;
use lofty::probe::Probe;
use lofty::tag::{Tag, TagType};

use crate::{AudioTags, CoverImage, CoverUpdate, TagError, TagUpdate};

/// music-tag-native reports a picture without a MIME type as JPEG.
const DEFAULT_COVER_MIME: &str = "image/jpeg";

pub(crate) fn read_tagged_file(file: &mut File) -> Result<TaggedFile, TagError> {
    file.rewind()?;
    Ok(Probe::new(BufReader::new(file)).guess_file_type()?.read()?)
}

/// Everything but word lyrics, which live outside the generic tag.
pub(crate) fn read(tagged: &TaggedFile, file_size: u64) -> AudioTags {
    let tag = readable_tag(tagged);
    let text = |get: fn(&Tag) -> Option<String>| tag.and_then(get);
    let properties = tagged.properties();
    let duration = properties.duration();

    AudioTags {
        title: text(|tag| tag.title().map(|value| value.into_owned())),
        artist: text(|tag| tag.artist().map(|value| value.into_owned())),
        album: text(|tag| tag.album().map(|value| value.into_owned())),
        lyrics: text(|tag| tag.get_string(lyrics_key(tag)).map(str::to_owned)),
        word_lyrics: None,
        duration_ms: i64::try_from(duration.as_millis()).unwrap_or(i64::MAX),
        bit_rate: properties
            .audio_bitrate()
            .or_else(|| estimated_bit_rate(file_size, duration))
            .and_then(|kbps| i32::try_from(kbps).ok()),
        sample_rate: properties.sample_rate().and_then(|hz| i32::try_from(hz).ok()),
        cover: tag.and_then(|tag| tag.pictures().first()).map(|picture| CoverImage {
            mime_type: picture.mime_type().map_or(DEFAULT_COVER_MIME, MimeType::as_str).to_owned(),
            data: picture.data().to_vec(),
        }),
    }
}

/// Whether the update touches title, artist, album, lyrics or the cover.
pub(crate) fn changes_fields(update: &TagUpdate) -> bool {
    update.title.is_some()
        || update.artist.is_some()
        || update.album.is_some()
        || update.lyrics.is_some()
        || !matches!(update.cover, CoverUpdate::Keep)
}

/// Title, artist, album and lyrics.
pub(crate) fn apply_text(tag: &mut Tag, update: &TagUpdate) {
    if let Some(title) = &update.title {
        tag.set_title(title.clone());
    }
    if let Some(artist) = &update.artist {
        tag.set_artist(artist.clone());
    }
    if let Some(album) = &update.album {
        tag.set_album(album.clone());
    }
    if let Some(lyrics) = &update.lyrics {
        let key = lyrics_key(tag);
        tag.insert_text(key, lyrics.clone());
    }
}

/// Pictures inside the tag; a replacement leaves exactly one front cover.
pub(crate) fn apply_cover(tag: &mut Tag, cover: &CoverUpdate) {
    match cover {
        CoverUpdate::Keep => {}
        CoverUpdate::Remove => remove_pictures_from(tag, 0),
        CoverUpdate::Replace { image } => {
            tag.set_picture(0, front_cover(image));
            remove_pictures_from(tag, 1);
        }
    }
}

pub(crate) fn front_cover(image: &CoverImage) -> Picture {
    Picture::unchecked(image.data.clone())
        .pic_type(PictureType::CoverFront)
        .mime_type(MimeType::from_str(&image.mime_type))
        .build()
}

/// Formats without a dedicated writer go through the generic tag as a whole,
/// as with music-tag-native, so items lofty cannot map are not kept.
pub(crate) fn write_generic(file: &mut File, update: &TagUpdate) -> Result<(), TagError> {
    if !changes_fields(update) {
        return Ok(());
    }
    let mut tagged = read_tagged_file(file)?;
    let tag = writable_tag(&mut tagged)?;
    apply_text(tag, update);
    apply_cover(tag, &update.cover);
    file.rewind()?;
    tagged.save_to(file, WriteOptions::default())?;
    Ok(())
}

fn readable_tag(tagged: &TaggedFile) -> Option<&Tag> {
    if !tagged.contains_tag() {
        return None;
    }
    tagged.primary_tag().or_else(|| tagged.first_tag())
}

fn writable_tag(tagged: &mut TaggedFile) -> Result<&mut Tag, TagError> {
    if !tagged.contains_tag() {
        let tag_type = tagged.file_type().primary_tag_type();
        tagged.insert_tag(Tag::new(tag_type));
    }
    let tag = if tagged.primary_tag().is_some() { tagged.primary_tag_mut() } else { tagged.first_tag_mut() };
    tag.ok_or_else(|| TagError::Malformed("no writable tag".to_owned()))
}

/// ID3v2 keeps lyrics in USLT; the other formats have a plain lyrics field.
fn lyrics_key(tag: &Tag) -> ItemKey {
    if tag.tag_type() == TagType::Id3v2 {
        ItemKey::UnsyncLyrics
    } else {
        ItemKey::Lyrics
    }
}

fn remove_pictures_from(tag: &mut Tag, first: usize) {
    for index in (first..tag.picture_count() as usize).rev() {
        tag.remove_picture(index);
    }
}

/// music-tag-native's fallback: file size over duration, kept within 8..=10000 kbps.
fn estimated_bit_rate(file_size: u64, duration: Duration) -> Option<u32> {
    let seconds = duration.as_secs_f64();
    if seconds <= f64::EPSILON {
        return None;
    }
    let kbps = ((file_size as f64 * 8.0) / (seconds * 1000.0)).round();
    (8.0..=10_000.0).contains(&kbps).then_some(kbps as u32)
}
