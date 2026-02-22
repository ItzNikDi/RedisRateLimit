package dev.nikdi.redisratelimit

import eu.vendeli.rethis.ReThis
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

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
     * **MUST** be set before the plugin is installed. Throws [IllegalArgumentException] if `null` at installation time.
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
     * A function that derives a string key from an [ApplicationCall], used to
     * identify and bucket requests for rate limiting purposes.
     *
     * Returns a client IP address or an authenticated user ID.
     * The final Redis key will be `ratelimit:<path>:<keySelector result>`.
     *
     * Defaults to the client's IP address via [clientIp].
     */
    var keySelector: (ApplicationCall) -> String = { call ->
        call.clientIp()
    }

    /**
     * A suspending callback invoked when a request exceeds the rate limit.
     *
     * Use this to customize the response returned to the client. The [RetryAfter][HttpHeaders.RetryAfter]
     * header is set automatically before this callback is invoked.
     *
     * Defaults to responding with [HttpStatusCode.TooManyRequests] and no message.
     */
    var onRateLimited: suspend (ApplicationCall) -> Unit = { call ->
        call.respond(HttpStatusCode.TooManyRequests)
    }
}
