package com.v1per.synthx.data.backup

import java.io.File

/**
 * Golden journal bytes from `app/src/test/resources/restore-journals/`. README.md there lists
 * the files and the step that updates the active golden when the database version bumps.
 */
internal object RestoreJournalGoldens {
    const val OPERATION_ID = "0123456789abcdef0123456789abcdef"
    const val OTHER_REQUEST_ID = "fedcba9876543210fedcba9876543210"
    const val SOURCE_NAME = "synthx_backup_2026-08-26_120000_000_0123abcd.db"

    const val V24_COMPLETE = "v2.4-complete.json"
    const val V25_COMPLETE_ROLLED_BACK = "v2.5-complete-rolled-back.json"
    const val ACTIVE_POST_VALIDATED = "active-post-validated.json"

    /** JVM tests run with `app/` as the working directory. */
    val jvmFolder = File("src/test/resources/restore-journals")
    val deviceFolder = File("src/androidTest/assets/restore-journals")

    fun read(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("restore-journals/$name")) {
            "Golden journal $name is missing from the test resources"
        }
        return stream.bufferedReader().use { it.readText() }
    }
}
