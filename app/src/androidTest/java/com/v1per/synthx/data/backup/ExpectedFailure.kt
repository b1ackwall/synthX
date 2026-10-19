package com.v1per.synthx.data.backup

import kotlin.reflect.KClass

/**
 * Shared check behind the `expectFailure`/`expectSuspendFailure` helpers of the restore
 * instrumented tests. Runs [block] and returns what it throws. Fails when [block] completes,
 * when the throwable is not a [type], or when its message does not contain [messageContains],
 * so a refusal for the wrong reason is a red test, not a pass.
 */
fun <T : Throwable> expectFailureOf(type: KClass<T>, messageContains: String, block: () -> Unit): T {
    val thrown = try {
        block()
        null
    } catch (error: Throwable) {
        error
    }
    return checkExpectedFailure(type, messageContains, thrown)
}

suspend fun <T : Throwable> expectSuspendFailureOf(
    type: KClass<T>,
    messageContains: String,
    block: suspend () -> Unit,
): T {
    val thrown = try {
        block()
        null
    } catch (error: Throwable) {
        error
    }
    return checkExpectedFailure(type, messageContains, thrown)
}

private fun <T : Throwable> checkExpectedFailure(type: KClass<T>, messageContains: String, thrown: Throwable?): T {
    val expected = "${type.java.name} with a message containing \"$messageContains\""
    if (thrown == null) throw AssertionError("Expected failure: $expected, but the block completed normally")
    if (!type.java.isInstance(thrown) || thrown.message?.contains(messageContains) != true) {
        throw AssertionError("Failed for the wrong reason: expected $expected, got $thrown", thrown)
    }
    @Suppress("UNCHECKED_CAST") // checked by isInstance above
    return thrown as T
}
