package com.lyricsadapter.pluginhost.spike

import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

class CheckResult(val name: String, val passed: Boolean, val elapsed: Duration, val detail: String)

/** Runs named checks, printing each as it finishes; a failed check records its error and continues. */
class CheckRunner {
    private val results = mutableListOf<CheckResult>()

    suspend fun <T> check(name: String, describe: (T) -> String = { it.toString() }, block: suspend () -> T): T? {
        val started = TimeSource.Monotonic.markNow()
        val outcome = try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
        val elapsed = started.elapsedNow()
        val result = outcome.fold(
            onSuccess = { CheckResult(name, true, elapsed, describe(it)) },
            onFailure = { CheckResult(name, false, elapsed, "${it::class.simpleName}: ${it.message}") },
        )
        results += result
        println("${if (result.passed) "PASS" else "FAIL"}  ${name.padEnd(34)} ${elapsed.inWholeMilliseconds.toString().padStart(6)} ms  ${result.detail}")
        return outcome.getOrNull()
    }

    /** Passes when [block] fails with a message matching [expected], e.g. a call that needs a login. */
    suspend fun expectFailure(name: String, expected: Regex, block: suspend () -> Unit) {
        check(name, { it }) {
            val error = try {
                block()
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error
            }
            val message = error?.message ?: error("succeeded, expected a failure matching $expected")
            require(expected.containsMatchIn(message)) { "unexpected failure: $message" }
            "rejected as expected: $message"
        }
    }

    fun summary(): Boolean {
        val failed = results.filterNot { it.passed }
        println()
        println("${results.size - failed.size}/${results.size} checks passed")
        failed.forEach { println("  failed: ${it.name} — ${it.detail}") }
        return failed.isEmpty()
    }
}
