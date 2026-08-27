package com.v1per.synthx.data.backup

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class SQLiteVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SQLiteVersion> {
    override fun compareTo(other: SQLiteVersion): Int =
        compareValuesBy(this, other, SQLiteVersion::major, SQLiteVersion::minor, SQLiteVersion::patch)

    companion object {
        val VACUUM_INTO_MINIMUM = SQLiteVersion(3, 27, 0)

        fun parse(raw: String): SQLiteVersion? {
            val parts = raw.trim().split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { part ->
                if (part.isEmpty() || part.any { !it.isDigit() }) return null
                part.toIntOrNull() ?: return null
            }
            return SQLiteVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

internal data class BackupFileIdentity(
    val stem: String,
    val databaseFileName: String = "$stem.db",
    val metadataFileName: String = "$stem.json",
    val temporaryDatabaseFileName: String = "$stem.db.partial",
    val temporaryMetadataFileName: String = "$stem.json.partial",
)

internal object BackupFileNames {
    private val newFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd_HHmmss_SSS")
    private val oldFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd_HHmmss")
    private val newPattern = Regex(
        "^synthx_backup_(\\d{4}-\\d{2}-\\d{2}_\\d{6}_\\d{3})_([0-9a-f]{8})\\.db$"
    )
    private val oldPattern = Regex("^synthx_backup_(\\d{4}-\\d{2}-\\d{2}_\\d{6})\\.db$")

    fun create(
        instant: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        randomId: String = UUID.randomUUID().toString().replace("-", "").take(8),
    ): BackupFileIdentity {
        require(randomId.matches(Regex("[0-9a-f]{8}"))) { "Backup random id must be 8 lowercase hex characters" }
        val timestamp = newFormatter.format(LocalDateTime.ofInstant(instant, zoneId))
        return BackupFileIdentity("synthx_backup_${timestamp}_$randomId")
    }

    fun isCompletedDatabaseName(name: String): Boolean =
        !name.contains(".partial") && (newPattern.matches(name) || oldPattern.matches(name))

    fun parseTimestampMillis(name: String, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        val (value, formatter) = when {
            newPattern.matches(name) -> newPattern.matchEntire(name)!!.groupValues[1] to newFormatter
            oldPattern.matches(name) -> oldPattern.matchEntire(name)!!.groupValues[1] to oldFormatter
            else -> return null
        }
        return try {
            LocalDateTime.parse(value, formatter).atZone(zoneId).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

internal data class ByteDigest(val sizeBytes: Long, val sha256: String)

internal object BackupHashing {
    fun hash(file: File): ByteDigest = FileInputStream(file).use(::hash)

    fun hash(input: InputStream): ByteDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            count += read
        }
        return ByteDigest(
            sizeBytes = count,
            sha256 = digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            },
        )
    }
}

@Serializable
internal data class BackupMetadata(
    val timestamp: Long,
    val tier: String,
    val reason: String? = null,
    val dbVersion: Int,
    val file: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
) {
    fun requireCompleteForPublication(): BackupMetadata {
        require(timestamp > 0) { "Backup metadata timestamp is invalid" }
        require(BackupTier.entries.any { it.name == tier }) { "Backup metadata tier is invalid" }
        require(dbVersion > 0) { "Backup metadata database version is invalid" }
        require(BackupFileNames.isCompletedDatabaseName(file)) { "Backup metadata filename is invalid" }
        require(sizeBytes != null && sizeBytes > 0) { "Backup metadata size is invalid" }
        require(sha256 != null && sha256.matches(Regex("[0-9a-f]{64}"))) {
            "Backup metadata SHA-256 is invalid"
        }
        return this
    }
}

internal object BackupMetadataCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    fun encode(metadata: BackupMetadata): ByteArray =
        json.encodeToString(BackupMetadata.serializer(), metadata.requireCompleteForPublication())
            .encodeToByteArray()

    fun decodeOrNull(bytes: ByteArray): BackupMetadata? = runCatching {
        json.decodeFromString(BackupMetadata.serializer(), bytes.decodeToString())
    }.getOrNull()
}
