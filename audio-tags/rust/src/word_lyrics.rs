//! QRC/YRC payloads stored as custom fields, read and written the way the
//! desktop's wordLyricsTagService does: keys match case-insensitively, a write
//! drops every case variant of the key and appends it in upper case, and QRC
//! wins when both formats are present. Other formats carry no word lyrics.

use std::fs::File;
use std::io::{BufReader, Seek};

use lofty::config::ParseOptions;
use lofty::file::{AudioFile, FileType};
use lofty::flac::FlacFile;
use lofty::id3::v2::{Frame, Id3v2Tag};
use lofty::mpeg::MpegFile;
use lofty::ogg::tag::VorbisComments;

use crate::{TagError, WordLyrics, WordLyricsFormat, WordLyricsUpdate};

pub(crate) fn read(file: &mut File, file_type: FileType) -> Result<Option<WordLyrics>, TagError> {
    let lyrics = match file_type {
        FileType::Flac => {
            let flac = FlacFile::read_from(&mut reader(file)?, ParseOptions::new())?;
            flac.vorbis_comments().and_then(|comments| preferred(|key| vorbis_value(comments, key)))
        }
        FileType::Mpeg => {
            let mpeg = MpegFile::read_from(&mut reader(file)?, ParseOptions::new())?;
            mpeg.id3v2().and_then(|tag| preferred(|key| user_text(tag, key)))
        }
        _ => None,
    };
    Ok(lyrics)
}

pub(crate) fn changes(update: &WordLyricsUpdate) -> bool {
    !matches!(update, WordLyricsUpdate::Keep)
}

pub(crate) fn apply_to_vorbis(comments: &mut VorbisComments, update: &WordLyricsUpdate) {
    if let Some((key, value)) = field_change(update) {
        let variants: Vec<String> = comments
            .items()
            .filter(|(name, _)| name.eq_ignore_ascii_case(key))
            .map(|(name, _)| name.to_owned())
            .collect();
        for name in variants {
            comments.remove(&name).for_each(drop);
        }
        if let Some(value) = value {
            comments.push(key.to_owned(), value.to_owned());
        }
    }
}

pub(crate) fn apply_to_id3v2(tag: &mut Id3v2Tag, update: &WordLyricsUpdate) {
    if let Some((key, value)) = field_change(update) {
        let variants: Vec<String> = tag
            .iter()
            .filter_map(|frame| match frame {
                Frame::UserText(text) if text.description.eq_ignore_ascii_case(key) => Some(text.description.to_string()),
                _ => None,
            })
            .collect();
        for description in variants {
            tag.remove_user_text(&description);
        }
        if let Some(value) = value {
            tag.insert_user_text(key.to_owned(), value.to_owned());
        }
    }
}

pub(crate) fn reader(file: &mut File) -> Result<BufReader<&mut File>, TagError> {
    file.rewind()?;
    Ok(BufReader::new(file))
}

/// The field to change and its new value; `None` removes it.
fn field_change(update: &WordLyricsUpdate) -> Option<(&'static str, Option<&str>)> {
    match update {
        WordLyricsUpdate::Keep => None,
        WordLyricsUpdate::Remove { format } => Some((field_name(*format), None)),
        WordLyricsUpdate::Replace { lyrics } => Some((field_name(lyrics.format), Some(lyrics.text.as_str()))),
    }
}

fn field_name(format: WordLyricsFormat) -> &'static str {
    match format {
        WordLyricsFormat::Qrc => "QRC",
        WordLyricsFormat::Yrc => "YRC",
    }
}

/// QRC first, then YRC; empty values count as absent, as on desktop.
fn preferred(lookup: impl Fn(&str) -> Option<String>) -> Option<WordLyrics> {
    [WordLyricsFormat::Qrc, WordLyricsFormat::Yrc].into_iter().find_map(|format| {
        lookup(field_name(format))
            .filter(|text| !text.is_empty())
            .map(|text| WordLyrics { format, text })
    })
}

fn vorbis_value(comments: &VorbisComments, key: &str) -> Option<String> {
    comments
        .items()
        .find(|(name, _)| name.eq_ignore_ascii_case(key))
        .map(|(_, value)| value.to_owned())
}

fn user_text(tag: &Id3v2Tag, key: &str) -> Option<String> {
    tag.iter().find_map(|frame| match frame {
        Frame::UserText(text) if text.description.eq_ignore_ascii_case(key) => Some(text.content.to_string()),
        _ => None,
    })
}
