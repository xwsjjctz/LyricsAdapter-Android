//! FLAC writes that keep every Vorbis comment. A generic-tag round trip would
//! drop the comments lofty has no key for, QRC/YRC among them; instead the
//! common fields are split out of the comments, edited and merged back.

use std::fs::File;
use std::io::Seek;

use lofty::config::{ParseOptions, WriteOptions};
use lofty::file::AudioFile;
use lofty::flac::FlacFile;
use lofty::ogg::OggPictureStorage;
use lofty::picture::PictureInformation;
use lofty::tag::{MergeTag, SplitTag};

use crate::common;
use crate::word_lyrics;
use crate::{CoverUpdate, TagError, TagUpdate};

pub(crate) fn write(file: &mut File, update: &TagUpdate) -> Result<(), TagError> {
    if !common::changes_fields(update) && !word_lyrics::changes(&update.word_lyrics) {
        return Ok(());
    }

    let mut flac = FlacFile::read_from(&mut word_lyrics::reader(file)?, ParseOptions::new())?;
    let mut comments = flac.remove_vorbis_comments().unwrap_or_default();
    if common::changes_fields(update) {
        let (remainder, mut tag) = comments.split_tag();
        common::apply_text(&mut tag, update);
        // Covers belong in FLAC picture blocks (below); a cover change also
        // clears any picture stored inside the comments.
        common::apply_cover(&mut tag, &cleared(&update.cover));
        comments = remainder.merge_tag(tag);
    }
    word_lyrics::apply_to_vorbis(&mut comments, &update.word_lyrics);
    flac.set_vorbis_comments(comments);

    if let CoverUpdate::Remove | CoverUpdate::Replace { .. } = &update.cover {
        flac.remove_pictures();
    }
    if let CoverUpdate::Replace { image } = &update.cover {
        let picture = common::front_cover(image);
        // Dimensions are only known for PNG and JPEG; like lofty's generic FLAC
        // writer, store zeroes for anything else rather than failing the save.
        let information = PictureInformation::from_picture(&picture).unwrap_or_default();
        flac.insert_picture(picture, Some(information))
            .map_err(|error| TagError::Malformed(error.to_string()))?;
    }

    file.rewind()?;
    flac.save_to(file, WriteOptions::default())?;
    Ok(())
}

fn cleared(cover: &CoverUpdate) -> CoverUpdate {
    match cover {
        CoverUpdate::Keep => CoverUpdate::Keep,
        CoverUpdate::Remove | CoverUpdate::Replace { .. } => CoverUpdate::Remove,
    }
}
