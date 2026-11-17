package dev.nikdi.redisratelimit

import eu.vendeli.rethis.ReThis
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * A selector that derives a rate-limiting key from an [ApplicationCall].
 *
 * @param description the value used in [toString]
 * @param selector the function used to derive the key
 */
open class KeySelector(
    private val description: String,
    private val selector: (ApplicationCall) -> String
) {
    open operator fun invoke(call: ApplicationCall): String = selector(call)

    companion object {
        /**
         * Uses the client's IP address as the rate-limiting key.
         * @see clientIp
         */
        val ClientIP = KeySelector("Client IP") { call -> call.clientIp() }
    }

    override fun toString(): String = description
}

/**
 * Configuration for the [RedisRateLimit] plugin.
 *
 * Must be configured with a [rethisInstance] before the plugin can be utilized.
 *
 * @see clientIp
 * @see RedisRateLimit
 */
class RedisRateLimitConfig {
    /**
     * The [ReThis] instance used to communicate with Redis.
     *
     * **MUST** be set before the plugin is installed. Throws [IllegalStateException] if `null` at installation time.
     */
    var rethisInstance: ReThis? = null

    /**
     * Maximum requests allowed within the [windowSeconds] duration.
     *
     * Defaults to `10`.
     */
    var maxRequests: Long = 10

    /**
     * The duration of the sliding rate limit window, in seconds.
     * The request counter for a given key resets after this period.
     *
     * Defaults to `60`.
     */
    var windowSeconds: Long = 60

    /**
     * Defines how a request is identified for rate limiting purposes by deriving
     * a string key from an [ApplicationCall].
     *
     * The resulting value is used as part of the Redis key:
     * `ratelimit:<path>:<keySelector result>`.
     *
     * Defaults to [KeySelector.ClientIP].
     */
    var keySelector: KeySelector = KeySelector.ClientIP

    /**
     * A suspending callback invoked when a request exceeds the rate limit.
     *
     * Use this to customize the response returned to the client. The [RetryAfter][HttpHeaders.RetryAfter]
     * header is set automatically before this callback is invoked.
     *
     * Defaults to responding with [HttpStatusCode.TooManyRequests] and no message.
     */
    var onRateLimited: suspend (ApplicationCall) -> Unit = { call -> call.respond(HttpStatusCode.TooManyRequests) }

    override fun toString(): String = """
        ${this::class.simpleName}(
            Max requests: $maxRequests
            Sliding window timeout: ${windowSeconds}s
            Key selector: $keySelector
        )
    """.trimIndent()
}
