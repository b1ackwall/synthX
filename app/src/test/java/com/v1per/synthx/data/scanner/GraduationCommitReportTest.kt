package com.v1per.synthx.data.scanner

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class GraduationCommitReportTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    /** Must not report Done here: that would leave the moved file for a scan to re-import. */
    @Test fun rowGoneAtCommitIsAFailureNotSuccess() = runTest {
        val stages = mutableListOf<GraduationStage>()
        val result = reportGraduationCommit(
            GraduationCommit.RowGone,
            songId = "song",
            publishedPath = "content://library/Song.mp3",
        ) { stages += it }

        assertEquals(GraduationResult.Failure(GRADUATION_ROW_GONE_REASON), result)
        assertEquals(listOf<GraduationStage>(GraduationStage.Failed(GRADUATION_ROW_GONE_REASON)), stages)
    }

    @Test fun updatedRowIsSuccess() = runTest {
        val stages = mutableListOf<GraduationStage>()
        val result = reportGraduationCommit(
            GraduationCommit.Updated(song = null),
            songId = "song",
            publishedPath = "content://library/Song.mp3",
        ) { stages += it }

        assertEquals(GraduationResult.Success, result)
        assertEquals(listOf<GraduationStage>(GraduationStage.Done), stages)
    }
}
