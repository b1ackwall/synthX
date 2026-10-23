package com.v1per.synthx.ui.screens.onboarding

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Recovery and fresh-install folder picks, and grant release when a root is superseded or rejected. */
@RunWith(AndroidJUnit4::class)
class OnboardingFolderPickInstrumentedTest : OnboardingRecoveryTestBase() {

    @Test fun recoveryAudioPicksPersistWithoutScansOrBackupAttempts() {
        val before = scanIds()
        saveMusic()
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertNull(preferences.newArrivalsFolderUri)
        composeRule.runOnUiThread { library.saveRecoveryNewArrivalsFolder(arrivals) }
        await(library.newArrivalsFolderUri) { it == arrivals }
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
        assertEquals(before, scanIds())
        assertEquals(123L, preferences.lastBackupAttemptTimestamp)
    }

    @Test fun nestedRecoveryPickKeepsThePreviouslyAcceptedFolder() {
        saveMusic()
        composeRule.runOnUiThread { library.saveRecoveryNewArrivalsFolder(arrivals) }
        await(library.newArrivalsFolderUri) { it == arrivals }
        val nested = "content://synthx.test.music/tree/primary%3AMusic%2FArrivals".toUri()
        composeRule.runOnUiThread { library.saveRecoveryNewArrivalsFolder(nested) }
        await(library.newArrivalsFolderError) { it != null }
        assertEquals(arrivals, library.newArrivalsFolderUri.value)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)

        val containsArrivals = "content://synthx.test.arrivals/tree/primary%3A".toUri()
        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(containsArrivals) }
        await(library.folderError) { it != null }
        assertEquals(music, library.musicFolderUri.value)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(123L, preferences.lastBackupAttemptTimestamp)
    }

    @Test fun sameFolderRejectionPreservesBothGrantsThroughFreshInstallSaveFunctions() {
        configureAudioRoots()
        persistedGrants += setOf(music, arrivals)

        composeRule.runOnUiThread { library.saveMusicFolder(arrivals) }
        await(library.folderError) { it != null }
        composeRule.runOnIdle { }
        assertEquals(music, library.musicFolderUri.value)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
        assertTrue(arrivals in persistedGrants)

        composeRule.runOnUiThread { library.saveNewArrivalsFolder(music) }
        await(library.newArrivalsFolderError) { it != null }
        composeRule.runOnIdle { }
        assertEquals(arrivals, library.newArrivalsFolderUri.value)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
        assertTrue(music in persistedGrants)
        assertTrue(releasedGrants.isEmpty())
    }

    @Test fun sameFolderRejectionPreservesBothGrantsThroughRecoverySaveFunctions() {
        configureAudioRoots()
        persistedGrants += setOf(music, arrivals)

        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(arrivals) }
        await(library.folderError) { it != null }
        composeRule.runOnIdle { }
        assertEquals(music, library.musicFolderUri.value)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
        assertTrue(arrivals in persistedGrants)

        composeRule.runOnUiThread { library.saveRecoveryNewArrivalsFolder(music) }
        await(library.newArrivalsFolderError) { it != null }
        composeRule.runOnIdle { }
        assertEquals(arrivals, library.newArrivalsFolderUri.value)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
        assertTrue(music in persistedGrants)
        assertTrue(releasedGrants.isEmpty())
    }

    @Test fun rejectedOrphanGrantIsStillReleased() {
        configureAudioRoots()
        val orphan = "content://synthx.test.arrivals/tree/primary%3AArrivals%2FRejected".toUri()
        persistedGrants += orphan

        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(orphan) }
        await(library.folderError) { it != null }
        composeRule.runOnIdle { }

        assertFalse(orphan in persistedGrants)
        assertEquals(listOf(orphan), releasedGrants)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
    }

    @Test fun rejectedBackupOwnedGrantIsPreserved() {
        configureAudioRoots()
        val backup = "content://synthx.test.arrivals/tree/primary%3AArrivals%2FBackups".toUri()
        preferences.backupFolderUri = backup.toString()
        persistedGrants += backup

        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(backup) }
        await(library.folderError) { it != null }
        composeRule.runOnIdle { }

        assertTrue(backup in persistedGrants)
        assertTrue(releasedGrants.isEmpty())
        assertEquals(backup.toString(), preferences.backupFolderUri)
        assertEquals(music.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
    }

    @Test fun supersededAudioRootDoesNotReleaseGrantStillOwnedByBackupRole() {
        configureAudioRoots()
        preferences.backupFolderUri = music.toString()
        persistedGrants += music
        val replacement = "content://synthx.test.replacement/tree/primary%3AReplacement".toUri()

        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(replacement) }
        await(library.musicFolderUri) { it == replacement }
        composeRule.runOnIdle { }

        assertTrue(music in persistedGrants)
        assertTrue(releasedGrants.isEmpty())
        assertEquals(music.toString(), preferences.backupFolderUri)
        assertEquals(replacement.toString(), preferences.musicFolderUri)
    }

    @Test fun genuinelySupersededAudioRootGrantIsStillReleased() {
        configureAudioRoots()
        persistedGrants += music
        val replacement = "content://synthx.test.replacement/tree/primary%3AReplacement".toUri()

        composeRule.runOnUiThread { library.saveRecoveryMusicFolder(replacement) }
        await(library.musicFolderUri) { it == replacement }
        composeRule.runOnIdle { }

        assertFalse(music in persistedGrants)
        assertEquals(listOf(music), releasedGrants)
        assertEquals(replacement.toString(), preferences.musicFolderUri)
        assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
    }
}
