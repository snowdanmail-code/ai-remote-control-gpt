package com.example.airemote

import java.net.UnknownHostException

/** A DNS retry before OAuth credentials are transmitted, never a retry of POST.
 * The HTTPS transport must actually attempt connect(); a stand-alone DNS gate
 * can reject a request that would otherwise succeed on the active network.
 */
object ChatGptTokenConnect {
    fun <T> openBeforeBody(
        create: () -> T,
        establish: (T) -> Unit,
        discard: (T) -> Unit,
        pauseBeforeRetry: () -> Unit = { Thread.sleep(350) },
        onFailure: (Throwable) -> Unit = {}
    ): T {
        for (attempt in 0..1) {
            var connection: T? = null
            try {
                val c = create()
                connection = c
                establish(c) // No credential-bearing bytes written yet.
                return c
            } catch (ex: UnknownHostException) {
                onFailure(ex)
                connection?.let { try { discard(it) } catch (_: Exception) {} }
                if (attempt == 1) throw ex
                pauseBeforeRetry()
            } catch (ex: Exception) {
                onFailure(ex)
                connection?.let { try { discard(it) } catch (_: Exception) {} }
                throw ex // TLS, HTTP, timeout and other errors are never retried.
            }
        }
        error("Unreachable: bounded attempts exhausted")
    }
}
