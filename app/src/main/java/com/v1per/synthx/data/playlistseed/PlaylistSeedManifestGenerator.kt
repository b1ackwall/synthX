package com.v1per.synthx.data.playlistseed

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.RepositoryDbOperation
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface PlaylistSeedManifestRegenerationResult {
    data class Success(val fileName: String) : PlaylistSeedManifestRegenerationResult
    data object NoBackupFolderConfigured : PlaylistSeedManifestRegenerationResult
    data object GeneratedManifestInvalid : PlaylistSeedManifestRegenerationResult
    data object WriteFailure : PlaylistSeedManifestRegenerationResult
}

/**
 * Developer-triggered counterpart to [PlaylistSeedBootstrapper]: instead of reading the
 * bundled, build-time-frozen asset, this reads the *current* live playlist state and writes a
 * byte-compatible [PlaylistSeedManifest] out to the SAF backup folder — a hand-off artifact a
 * developer can manually copy into a future build's `assets/playlist_seed_manifest_v1.json`,
 * not something the running app can replace in place (assets are read-only at runtime).
 *
 * Deliberately flat, matching [PlaylistSeedPlaylist]'s own shape: grouped and ungrouped
 * playlists are both flattened into one list with group membership discarded, since seeding
 * has never represented groups (see [PlaylistSeedBootstrapper.seed] — every seeded playlist
 * gets `groupId = null`).
 */
class PlaylistSeedManifestGenerator(
    private val context: Context,
    private val playlistDao: PlaylistDao,
) {
    private val preferences = PreferencesManager(context)
    private val json = Json { prettyPrint = true }

    suspend fun regenerate(): PlaylistSeedManifestRegenerationResult = withContext(Dispatchers.IO) {
        val treeUri = preferences.backupFolderUri?.let { Uri.parse(it) }
            ?: return@withContext PlaylistSeedManifestRegenerationResult.NoBackupFolderConfigured

        val manifest = RepositoryDbOperation.run { assembleManifest() }

        // Self-check against the exact same validator PlaylistSeedBootstrapper uses to accept
        // a manifest, so a malformed generation is caught here rather than silently written
        // and only discovered the next time someone tries to seed from it.
        if (!PlaylistSeedManifestValidator.isValid(manifest)) {
            return@withContext PlaylistSeedManifestRegenerationResult.GeneratedManifestInvalid
        }

        val bytes = json.encodeToString(PlaylistSeedManifest.serializer(), manifest).toByteArray()

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext PlaylistSeedManifestRegenerationResult.WriteFailure

        val write = runCatching {
            // Leftover from a previous run that crashed before reaching the rename below.
            root.findFile(TEMP_FILE_NAME)?.delete()

            val tempDoc = root.createFile(MIME_JSON, TEMP_FILE_NAME)
                ?: error("Could not create seed manifest temp file")
            context.contentResolver.openOutputStream(tempDoc.uri)?.use { out -> out.write(bytes) }
                ?: error("Could not open seed manifest temp file for writing")

            // A manually-triggered, infrequent developer action with no automatic retry and no
            // history requirement (unlike PlaylistExportManager's export) — a plain
            // temp-write-then-promote is enough write-safety: the previous manifest, if any,
            // is only replaced once the new one is fully written and closed.
            root.findFile(FINAL_FILE_NAME)?.delete()
            if (!tempDoc.renameTo(FINAL_FILE_NAME)) error("Could not finalize seed manifest")
        }

        write.fold(
            onSuccess = { PlaylistSeedManifestRegenerationResult.Success(FINAL_FILE_NAME) },
            onFailure = { PlaylistSeedManifestRegenerationResult.WriteFailure },
        )
    }

    private suspend fun assembleManifest(): PlaylistSeedManifest {
        val ungroupedPlaylists = playlistDao.getUngroupedPlaylists().first()
        val groupedPlaylists = playlistDao.getAllGroups().first()
            .flatMap { group -> playlistDao.getPlaylistsForGroup(group.id).first() }

        // Flattened — group membership is deliberately discarded, matching the manifest
        // format's own shape (PlaylistSeedPlaylist has no group field) and seeding's own
        // behavior (every seeded playlist gets groupId = null regardless of source grouping).
        val playlists = (ungroupedPlaylists + groupedPlaylists).map { playlist ->
            PlaylistSeedPlaylist(
                playlistId = playlist.id,
                name = playlist.name,
                // Position-ordered, matching how PlaylistSeedBootstrapper.seed() consumes this
                // list — songHashes order becomes each restored membership's position.
                songHashes = playlistDao.getSongIdsForPlaylist(playlist.id),
            )
        }

        return PlaylistSeedManifest(
            schemaVersion = PlaylistSeedManifestV1.SCHEMA_VERSION,
            hashStrategy = PlaylistSeedManifestV1.HASH_STRATEGY,
            playlists = playlists,
        )
    }

    companion object {
        private const val MIME_JSON = "application/json"
        private const val FINAL_FILE_NAME = "playlist_seed_manifest.json"
        private const val TEMP_FILE_NAME = "playlist_seed_manifest.json.tmp"

        fun create(context: Context): PlaylistSeedManifestGenerator {
            val appContext = context.applicationContext
            val db = SynthXDatabase.getInstance(appContext)
            return PlaylistSeedManifestGenerator(
                context = appContext,
                playlistDao = db.playlistDao(),
            )
        }
    }
}
