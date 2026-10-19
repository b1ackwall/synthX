package com.v1per.synthx.testing

import java.io.File

/**
 * Read-only access to production Kotlin sources for the few tests that check wiring by reading
 * code. [codeOnly] drops comments first, so a comment that mentions a call can neither satisfy
 * nor break a check; [definingFile] finds a declaration wherever it lives, so a move-only split
 * of a file does not turn such a check red.
 */
object ProductionSource {
    private val root: File = listOf(File("src/main/java"), File("app/src/main/java"))
        .firstOrNull(File::isDirectory) ?: error("Could not locate production sources")

    fun file(relative: String): File =
        File(root, relative).takeIf(File::isFile) ?: error("Could not locate production source $relative")

    fun code(relative: String): String = codeOnly(file(relative).readText())

    /** Comment-free code of every production Kotlin file, keyed by its path under the source root. */
    fun allCode(): Map<String, String> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(root).path to codeOnly(it.readText()) }

    /** The comment-free code of the single production file containing [declaration]. */
    fun definingFile(declaration: String): String {
        val matches = allCode().filterValues { declaration in it }
        check(matches.size == 1) { "Expected one production file declaring \"$declaration\", found ${matches.keys}" }
        return matches.values.single()
    }

    /** Removes `//` and `/* */` comments, leaving string and char literals untouched. */
    fun codeOnly(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val rest = source.length - i
            when {
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                    out.append(source, i, end)
                    i = end
                }

                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    var j = i + 1
                    while (j < source.length && source[j] != quote && source[j] != '\n') {
                        j += if (source[j] == '\\') 2 else 1
                    }
                    val end = minOf(j + 1, source.length)
                    out.append(source, i, end)
                    i = end
                }

                rest >= 2 && source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') i++
                }

                rest >= 2 && source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                    out.append(' ')
                }

                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}
