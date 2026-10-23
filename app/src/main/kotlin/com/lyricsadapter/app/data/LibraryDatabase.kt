package com.lyricsadapter.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.lyricsadapter.library.Track
import com.lyricsadapter.lyrics.WordLyricsFormat
import kotlinx.coroutines.flow.Flow

/** A local track and its place in the library's order. */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val coverPath: String?,
    val lyrics: String?,
    val wordLyrics: String?,
    val wordLyricsFormat: String?,
    val addedAtMs: Long,
    val position: Int,
) {
    fun toTrack() = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverPath = coverPath,
        lyrics = lyrics,
        wordLyrics = wordLyrics,
        wordLyricsFormat = wordLyricsFormat?.let { name -> WordLyricsFormat.entries.firstOrNull { it.name == name } },
        addedAtMs = addedAtMs,
    )
}

fun Track.toEntity(position: Int) = TrackEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    coverPath = coverPath,
    lyrics = lyrics,
    wordLyrics = wordLyrics,
    wordLyricsFormat = wordLyricsFormat?.name,
    addedAtMs = addedAtMs,
    position = position,
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY position")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun find(id: String): TrackEntity?

    @Query("SELECT id, position FROM tracks")
    suspend fun positions(): List<TrackPosition>

    @Upsert
    suspend fun upsert(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}

data class TrackPosition(val id: String, val position: Int)

@Database(entities = [TrackEntity::class], version = 1, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao

    companion object {
        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
    }
}
