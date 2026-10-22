//! Audio tag reading and writing for LyricsAdapter, exported through UniFFI.
//!
//! Field semantics follow the desktop app, which reads and writes the common
//! fields with music-tag-native (also lofty) and stores word lyrics with its
//! own writers (`electron/services/audioMetadataService.ts` and
//! `wordLyricsTagService.ts` in the LyricsAdapter repository):
//!
//! - the primary tag, or else the first tag, is read and written; a file
//!   without tags gets its format's primary tag on first write;
//! - ID3v2 lyrics live in `USLT`, other formats use their lyrics field;
//! - QRC/YRC word lyrics are a Vorbis comment field `QRC`/`YRC` in FLAC or an
//!   ID3v2 `TXXX:QRC`/`TXXX:YRC` frame in MP3, matched case-insensitively.
//!
//! Unlike music-tag-native, FLAC and MP3 edits keep the items lofty cannot map
//! (see `flac` and `mpeg`), and word lyrics are written in the same save.

mod common;
mod flac;
mod mpeg;
mod word_lyrics;

use std::fs::{File, OpenOptions};
use std::io::{BufReader, Seek};

use lofty::error::{FileEncodingError, FileParseError};
use lofty::file::{FileType, TaggedFileExt};
use lofty::probe::Probe;

uniffi::setup_scaffolding!();

/// Flat, so Kotlin exceptions carry the Display text as their message.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum TagError {
    #[error("I/O error: {0}")]
    Io(String),
    #[error("unsupported audio file: {0}")]
    Unsupported(String),
    #[error("unreadable tag data: {0}")]
    Malformed(String),
}

impl From<std::io::Error> for TagError {
    fn from(error: std::io::Error) -> Self {
        TagError::Io(error.to_string())
    }
}

impl From<FileParseError> for TagError {
    fn from(error: FileParseError) -> Self {
        if error.is_unknown_format() {
            TagError::Unsupported(error.to_string())
        } else {
            from_lofty(&error)
        }
    }
}

impl From<FileEncodingError> for TagError {
    fn from(error: FileEncodingError) -> Self {
        from_lofty(&error)
    }
}

/// I/O failures reported through lofty keep their kind; anything else is bad tag data.
fn from_lofty(error: &(dyn std::error::Error + 'static)) -> TagError {
    let message = error.to_string();
    let mut cause = error.source();
    while let Some(current) = cause {
        if current.is::<std::io::Error>() {
            return TagError::Io(message);
        }
        cause = current.source();
    }
    TagError::Malformed(message)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum WordLyricsFormat {
    Qrc,
    Yrc,
}

/// A QRC or YRC payload as the lyric parser consumes it (decrypted QRC XML, plain YRC).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WordLyrics {
    pub format: WordLyricsFormat,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CoverImage {
    pub mime_type: String,
    pub data: Vec<u8>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AudioTags {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    /// Lyrics as stored: plain text or LRC.
    pub lyrics: Option<String>,
    /// QRC wins when a file carries both formats.
    pub word_lyrics: Option<WordLyrics>,
    // Signed so Kotlin sees Long/Int rather than unsigned types.
    pub duration_ms: i64,
    /// kbps; estimated from file size and duration when the format reports none.
    pub bit_rate: Option<i32>,
    pub sample_rate: Option<i32>,
    /// The first embedded picture.
    pub cover: Option<CoverImage>,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum CoverUpdate {
    Keep,
    Remove,
    Replace { image: CoverImage },
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum WordLyricsUpdate {
    Keep,
    Remove { format: WordLyricsFormat },
    Replace { lyrics: WordLyrics },
}

/// Changes to apply; `None` text fields and `Keep` leave the stored value alone.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TagUpdate {
    #[uniffi(default = None)]
    pub title: Option<String>,
    #[uniffi(default = None)]
    pub artist: Option<String>,
    #[uniffi(default = None)]
    pub album: Option<String>,
    #[uniffi(default = None)]
    pub lyrics: Option<String>,
    pub cover: CoverUpdate,
    pub word_lyrics: WordLyricsUpdate,
}

/// Word lyrics a format cannot store are reported here, while the other
/// changes are still saved, as desktop does for M4A.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TagWriteReport {
    pub word_lyrics_error: Option<String>,
}

/// Reads tags from an open descriptor, such as one from a Storage Access
/// Framework URI. The descriptor is duplicated; the caller still owns it.
#[uniffi::export]
pub fn read_tags_from_fd(fd: i32) -> Result<AudioTags, TagError> {
    read_tags(&mut duplicate_fd(fd)?)
}

#[uniffi::export]
pub fn read_tags_from_path(path: String) -> Result<AudioTags, TagError> {
    read_tags(&mut File::open(path)?)
}

/// Writes in place through a descriptor opened for reading and writing ("rw").
#[uniffi::export]
pub fn write_tags_to_fd(fd: i32, update: TagUpdate) -> Result<TagWriteReport, TagError> {
    write_tags(&mut duplicate_fd(fd)?, &update)
}

#[uniffi::export]
pub fn write_tags_to_path(path: String, update: TagUpdate) -> Result<TagWriteReport, TagError> {
    write_tags(&mut OpenOptions::new().read(true).write(true).open(path)?, &update)
}

fn read_tags(file: &mut File) -> Result<AudioTags, TagError> {
    let tagged = common::read_tagged_file(file)?;
    let tags = common::read(&tagged, file.metadata()?.len());
    let word_lyrics = word_lyrics::read(file, tagged.file_type())?;
    Ok(AudioTags { word_lyrics, ..tags })
}

fn write_tags(file: &mut File, update: &TagUpdate) -> Result<TagWriteReport, TagError> {
    match file_type(file)? {
        FileType::Flac => flac::write(file, update)?,
        FileType::Mpeg => mpeg::write(file, update)?,
        other => {
            common::write_generic(file, update)?;
            if word_lyrics::changes(&update.word_lyrics) {
                let error = TagError::Unsupported(format!("{other:?} files carry no word lyrics"));
                return Ok(TagWriteReport { word_lyrics_error: Some(error.to_string()) });
            }
        }
    }
    Ok(TagWriteReport { word_lyrics_error: None })
}

fn file_type(file: &mut File) -> Result<FileType, TagError> {
    file.rewind()?;
    Probe::new(BufReader::new(file))
        .guess_file_type()?
        .file_type()
        .ok_or_else(|| TagError::Unsupported("unrecognised audio format".to_owned()))
}

#[cfg(unix)]
fn duplicate_fd(fd: i32) -> Result<File, TagError> {
    use std::os::fd::BorrowedFd;
    if fd < 0 {
        return Err(TagError::Io(format!("invalid file descriptor {fd}")));
    }
    // SAFETY: the caller keeps `fd` open for the duration of this call; the
    // duplicate is owned and closed here, the original stays with the caller.
    let borrowed = unsafe { BorrowedFd::borrow_raw(fd) };
    Ok(File::from(borrowed.try_clone_to_owned()?))
}

#[cfg(not(unix))]
fn duplicate_fd(_fd: i32) -> Result<File, TagError> {
    Err(TagError::Unsupported("file descriptors are only supported on Unix".to_owned()))
}
