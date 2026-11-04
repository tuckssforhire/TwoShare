package com.twoshare.app.ui

import kotlinx.coroutines.CancellationException

/**
 * A composable's LaunchedEffect and remembered coroutine scope are cancelled
 * when the screen leaves composition. Cancellation is not a network/UI error.
 *
 * Kotlin runCatching catches CancellationException, so it must not be used
 * directly around suspending work whose failures are displayed to the user.
 */
inline fun <T> runCatchingActive(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
