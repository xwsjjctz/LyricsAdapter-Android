//! MP3 writes that keep every ID3v2 frame. The common fields are split out of
//! the ID3v2 tag, edited through the generic tag and merged back, so frames
//! lofty cannot map survive.

use std::fs::File;
use std::io::Seek;

use lofty::config::{ParseOptions, WriteOptions};
use lofty::file::AudioFile;
use lofty::id3::v2::Id3v2Tag;
use lofty::mpeg::MpegFile;
use lofty::tag::{MergeTag, SplitTag, Tag};

use crate::common;
use crate::word_lyrics;
use crate::{TagError, TagUpdate};

pub(crate) fn write(file: &mut File, update: &TagUpdate) -> Result<(), TagError> {
    if !common::changes_fields(update) && !word_lyrics::changes(&update.word_lyrics) {
        return Ok(());
    }

    let mut mpeg = MpegFile::read_from(&mut word_lyrics::reader(file)?, ParseOptions::new())?;
    let mut id3v2 = match mpeg.remove_id3v2() {
        Some(tag) => tag,
        // Readers prefer ID3v2, so a new one starts from the ID3v1 fields
        // instead of hiding them behind a tag with only the edited ones.
        None => mpeg.id3v1().cloned().map(|v1| Id3v2Tag::from(Tag::from(v1))).unwrap_or_default(),
    };
    if common::changes_fields(update) {
        let (remainder, mut tag) = id3v2.split_tag();
        common::apply_text(&mut tag, update);
        common::apply_cover(&mut tag, &update.cover);
        id3v2 = remainder.merge_tag(tag);
    }
    word_lyrics::apply_to_id3v2(&mut id3v2, &update.word_lyrics);
    mpeg.set_id3v2(id3v2);

    file.rewind()?;
    mpeg.save_to(file, WriteOptions::default())?;
    Ok(())
}
