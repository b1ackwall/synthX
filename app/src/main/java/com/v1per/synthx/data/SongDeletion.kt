package com.v1per.synthx.data

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.db.AlbumDao
import com.v1per.synthx.data.db.SongDao
import java.io.File
import kotlinx.coroutines.CancellationException

private const val TAG = "SongDeletion"

/**
 * Re-points the song's album cover if this song was the one supplying it, then deletes the
 * song row. FK CASCADE already handles song_artists/playlist_songs/queue rows on its own;
 * this covers what a plain FK can't — the conditional album-art reassignment.
 *
 * Shared between [com.v1per.synthx.data.repository.SongRepositoryImpl.deleteSong] and
 * [com.v1per.synthx.data.scanner.FolderScanner]'s two internal evictions (path-collision
 * occupant, stale same-path/different-hash row) so both deletion paths do the same cleanup
 * instead of FolderScanner's bypassing it. Callers must already be inside a DB transaction —
 * this does not open its own.
 *
 * No-op if [songId] doesn't resolve to a row (already gone).
 */
suspend fun deleteSongRowWithArtRepoint(songDao: SongDao, albumDao: AlbumDao, songId: String) {
    val song = songDao.getSongById(songId) ?: return
    val albumId = song.albumId
    val artUri = song.albumArtUri

    // Only re-point when this song is the one the album borrowed its cover from;
    // reassigning unconditionally could replace good art with a null.
    if (albumId != null && artUri != null) {
        val album = albumDao.getAlbumById(albumId)
        if (album?.albumArtUri == artUri) {
            albumDao.updateAlbumArt(
                id = albumId,
                albumArtUri = songDao.findReplacementAlbumArt(albumId, songId),
            )
        }
    }
    songDao.deleteSongById(songId)
}

/**
 * Deletes a song's embedded-art files from filesDir/art/, if it has any. Best-effort (returns
 * false when the song never had embedded art, which is normal). Must only be called after the
 * transaction that deleted the song row has committed — a file delete cannot be rolled back
 * alongside the DB.
 *
 * Matches by songId prefix rather than a fixed extension: older art was written as
 * `art_<id>.png`, newer art as `art_<id>.jpg` (see FolderScanner.extractAlbumArt) — both must
 * be deletable without knowing which format a given song's file was written in. Also matches
 * the untouched-original full-resolution copy, `art_full_<id>.<ext>`, whose extension varies
 * by sniffed source format (jpg/png/webp).
 */
fun deleteSongArtFile(context: Context, songId: String): Boolean {
    val artDir = File(context.filesDir, "art")
    val matches = artDir.listFiles { file -> songIdOfArtFile(file.name) == songId } ?: return false
    return matches.fold(false) { deletedAny, file -> file.delete() || deletedAny }
}

/**
 * Outcome of [deleteSongAudioFile]. [ConfirmedAbsent] means the provider resolved the document and
 * positively reported that it does not exist. [Unknown] means resolution or the existence probe
 * failed, so no claim can be made about whether the file remains on disk.
 */
enum class AudioFileDeletion { Deleted, ConfirmedAbsent, Unknown, Failed }

/**
 * Permanently deletes the song's own audio file from disk, given the `file_path` its row carried.
 *
 * Follows [com.v1per.synthx.data.scanner.graduateSong]'s established SAF pattern exactly — parse
 * the stored path as a [Uri], resolve it with [DocumentFile.fromSingleUri], check existence, then
 * call `delete()` guarded by try/catch **and** with its Boolean return checked, since a provider
 * can refuse a delete without throwing. That is the whole reason this cannot be a bare
 * `doc.delete()`: a silently-false return would otherwise read as success.
 *
 * Must only be called after the transaction that deleted the song row has committed, and never
 * from inside a [com.v1per.synthx.data.repository.RepositoryDbOperation] transaction — same rule
 * (and same reason) as [deleteSongArtFile]: a file delete cannot be rolled back alongside the DB.
 * The database stays the source of truth either way; [Failed] confirms an orphaned file, while
 * [Unknown] preserves an inaccessible or otherwise unverifiable state instead of claiming success.
 */
fun deleteSongAudioFile(context: Context, filePath: String): AudioFileDeletion {
    val uri = runCatching { filePath.toUri() }.getOrNull()
        ?: return AudioFileDeletion.Failed.also { Log.w(TAG, "Unparseable song file path") }
    val document = try {
        DocumentFile.fromSingleUri(context, uri)
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Audio document resolution threw", e)
        return AudioFileDeletion.Unknown
    } ?: return AudioFileDeletion.Unknown.also { Log.w(TAG, "Could not resolve song audio document") }

    // DocumentFile.exists() catches provider-query exceptions internally and returns false, which
    // makes a revoked grant indistinguishable from a confirmed absence. Query directly so only a
    // successful, empty result earns ConfirmedAbsent; a null cursor or throwable stays Unknown.
    val exists = try {
        context.contentResolver.query(
            document.uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { cursor -> cursor.count > 0 }
            ?: return AudioFileDeletion.Unknown.also {
                Log.w(TAG, "Audio file existence check returned no result")
            }
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Audio file existence check threw", e)
        return AudioFileDeletion.Unknown
    }
    if (!exists) return AudioFileDeletion.ConfirmedAbsent

    val deleted = try {
        document.delete()
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Audio file delete threw", e)
        false
    }
    if (deleted) return AudioFileDeletion.Deleted
    // Distinguished from ConfirmedAbsent and Unknown: exists() succeeded and confirmed the file
    // before the provider refused its deletion.
    Log.w(TAG, "Provider refused to delete the song's audio file")
    return AudioFileDeletion.Failed
}

/**
 * Bulk counterpart to [deleteSongArtFile], for callers holding many ids at once. Returns the
 * number of files actually deleted.
 *
 * Exists because [deleteSongArtFile] lists `filesDir/art/` once per id, which is the right
 * shape for the handful of evictions a committed scan reconciles but not for the thousands of
 * ids a scan cancelled mid-import can have written art for — that would be one directory
 * listing per song, on the cancellation path, where taking tens of seconds risks the worker
 * being killed before it finishes cleaning up at all. This walks the directory once instead.
 *
 * Same matching rules as [deleteSongArtFile], via the same [songIdOfArtFile], so the two
 * cannot drift apart.
 */
fun deleteSongArtFiles(context: Context, songIds: Set<String>): Int {
    if (songIds.isEmpty()) return 0
    val artDir = File(context.filesDir, "art")
    val matches = artDir.listFiles { file -> songIdOfArtFile(file.name) in songIds } ?: return 0
    return matches.count { it.delete() }
}

/**
 * Whether [songId]'s compressed thumbnail (`art_<id>.jpg`, see
 * [com.v1per.synthx.data.scanner.AlbumArtExtractor]) is actually present on disk right now —
 * independent of what `album_art_uri` says the song's row points at. Fixed extension, so this
 * is a direct existence check rather than a directory scan.
 *
 * Used (alongside [fullResArtFileExists]) by [com.v1per.synthx.data.scanner.ArtRecovery] to
 * detect art lost outside a normal delete, e.g. an uninstall/reinstall wiping `filesDir` since
 * album art is deliberately excluded from backups. The two variants are checked independently
 * — a song can be missing one while still having the other (compressed extraction can fail on
 * its own while the full-res copy already succeeded, see [com.v1per.synthx.data.scanner.AlbumArtExtractor]).
 */
fun compressedArtFileExists(context: Context, songId: String): Boolean =
    compressedArtFileExists(File(context.filesDir, "art"), songId)

/** [compressedArtFileExists] split on the directory argument so it's testable with a plain JVM
 *  temp dir, with no Android [Context] needed. */
internal fun compressedArtFileExists(artDir: File, songId: String): Boolean =
    File(artDir, "art_$songId.jpg").exists()

/**
 * Whether [songId]'s full-resolution copy (`art_full_<id>.<ext>`) is actually present on disk
 * right now — independent of what `album_art_full_uri` says the song's row points at. Unlike
 * [compressedArtFileExists], the extension varies by sniffed source format (jpg/png/webp), so
 * this still requires a directory scan, matching the `art_full_` prefix exclusively so it can
 * never be satisfied by a compressed-only match.
 */
fun fullResArtFileExists(context: Context, songId: String): Boolean =
    fullResArtFileExists(File(context.filesDir, "art"), songId)

/** [fullResArtFileExists] split on the directory argument so it's testable with a plain JVM
 *  temp dir, with no Android [Context] needed. */
internal fun fullResArtFileExists(artDir: File, songId: String): Boolean =
    artDir.listFiles { file -> fullResSongIdOfArtFile(file.name) == songId }?.isNotEmpty() == true

/**
 * Which songs have each art variant present on disk, resolved from a SINGLE listing of
 * `filesDir/art/`.
 *
 * Exists for the same reason [deleteSongArtFiles] exists alongside [deleteSongArtFile]: the
 * per-song helpers above are the right shape for one song, but [fullResArtFileExists] costs a
 * full directory listing each time, so asking it about every song in the library is one listing
 * per song — O(N x N) filename comparisons for a library of ~2000 songs, which is what made
 * [com.v1per.synthx.data.scanner.ArtRecovery.findMissingArt]'s whole-library pass take tens of
 * seconds. Building both lookup sets from one walk makes that pass O(N).
 *
 * The membership rules are kept identical to the two per-song helpers on purpose — see
 * [ArtFilePresence.hasCompressed]/[ArtFilePresence.hasFullRes] — so a whole-library sweep and a
 * single-song re-check can never disagree about the same file.
 */
data class ArtFilePresence(
    private val compressedSongIds: Set<String>,
    private val fullResSongIds: Set<String>,
) {
    /** Same rule as [compressedArtFileExists]: an exact `art_<id>.jpg` entry. */
    fun hasCompressed(songId: String): Boolean = songId in compressedSongIds

    /** Same rule as [fullResArtFileExists]: an `art_full_<id>.<ext>` entry, any extension. */
    fun hasFullRes(songId: String): Boolean = songId in fullResSongIds
}

fun artFilePresence(context: Context): ArtFilePresence =
    artFilePresence(File(context.filesDir, "art"))

/**
 * [artFilePresence] split on the directory argument, same testability split as the two per-song
 * helpers above. An unreadable or absent directory yields empty sets — the same answer the
 * per-song helpers give for that case ("no art present"), not an error.
 */
internal fun artFilePresence(artDir: File): ArtFilePresence {
    val names = artDir.list() ?: return ArtFilePresence(emptySet(), emptySet())
    val compressed = mutableSetOf<String>()
    val fullRes = mutableSetOf<String>()
    for (name in names) {
        // `art_full_` first — it is also a valid `art_` prefix, same ordering rule as
        // songIdOfArtFile below.
        val fullResId = fullResSongIdOfArtFile(name)
        if (fullResId != null) {
            fullRes += fullResId
            continue
        }
        // Deliberately exact-match `art_<id>.jpg` rather than reusing songIdOfArtFile's
        // substringBefore('.'): compressedArtFileExists tests for that one filename, so a
        // leftover `art_<id>.<uuid>.tmp` or a legacy `art_<id>.png` must NOT satisfy this.
        if (name.startsWith("art_") && name.endsWith(".jpg")) {
            name.removePrefix("art_").removeSuffix(".jpg")
                .takeIf { it.isNotEmpty() }
                ?.let { compressed += it }
        }
    }
    return ArtFilePresence(compressed, fullRes)
}

/** Full-res-only counterpart to [songIdOfArtFile] — matches `art_full_<id>.<ext>` exclusively,
 *  so [fullResArtFileExists] can't be satisfied by a compressed-only file. */
private fun fullResSongIdOfArtFile(name: String): String? =
    name.takeIf { it.startsWith("art_full_") }
        ?.removePrefix("art_full_")
        ?.substringBefore('.')
        ?.takeIf { it.isNotEmpty() }

/**
 * The song id an art filename belongs to, or null if the name isn't art at all.
 *
 * The single definition of the `art_<id>.<ext>` / `art_full_<id>.<ext>` convention that both
 * deletion helpers match on. Matching by id rather than a fixed extension is deliberate:
 * older art was written as `art_<id>.png`, newer art as `art_<id>.jpg` (see
 * [com.v1per.synthx.data.scanner.AlbumArtExtractor]), and the untouched-original
 * full-resolution copy's extension varies by sniffed source format (jpg/png/webp). Stopping
 * at the first `.` also catches an interrupted write's `art_<id>.<uuid>.tmp` leftovers, which
 * belong to the same song and should go with it.
 *
 * `art_full_` is tested first because it is also a valid `art_` prefix.
 */
private fun songIdOfArtFile(name: String): String? {
    val withoutPrefix = when {
        name.startsWith("art_full_") -> name.removePrefix("art_full_")
        name.startsWith("art_") -> name.removePrefix("art_")
        else -> return null
    }
    return withoutPrefix.substringBefore('.').takeIf { it.isNotEmpty() }
}
