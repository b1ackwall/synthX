package com.v1per.synthx.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [ViewModel.viewModelScope]`.launch` with a failure boundary. A bare repository suspend call
 * inside `viewModelScope.launch` has nothing upstream to catch it — no installed
 * CoroutineExceptionHandler exists anywhere in this app — so an uncaught Room/SAF exception
 * crashes the process outright (N10/general 7.2). [onError] is called on the same dispatcher
 * as [block] and defaults to a no-op for call sites with nothing better to do than swallow it.
 */
fun ViewModel.launchCatching(
    onError: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
) {
    viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e)
        }
    }
}
