package dev.nikdi.redisratelimit

import eu.vendeli.rethis.ReThis
import eu.vendeli.rethis.command.generic.expire
import eu.vendeli.rethis.command.string.incr
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlin.time.Duration.Companion.seconds

/**
 * A **route-scoped** Ktor plugin that enforces rate limiting via Redis as the backing store.
 *
 * Tracks request counts per **route and key** (as determined by [RedisRateLimitConfig.keySelector]),
 * storing counters in Redis with an automatic expiry. When a client exceeds
 * [RedisRateLimitConfig.maxRequests] within [RedisRateLimitConfig.windowSeconds] seconds,
 * the [RedisRateLimitConfig.onRateLimited] callback is invoked and **the request is short-circuited**.
 *
 * Redis keys follow the **`ratelimit:<path>:<key>`** pattern.
 *
 * **Requires** a configured [ReThis] instance provided via [RedisRateLimitConfig.rethisInstance].
 *
 * Example usage:
 * ```
 * routing {
 *     route("/hello") {
 *         install(RedisRateLimit) {
 *             rethisInstance = myReThis
 *             maxRequests = 50
 *             windowSeconds = 30
 *         }
 *         get { ... }
 *     }
 * }
 * ```
 * @see RedisRateLimitConfig
 */
val RedisRateLimit = createRouteScopedPlugin(
    name = "RedisRateLimit",
    createConfiguration = ::RedisRateLimitConfig
) {

    val rethis: ReThis = requireNotNull(pluginConfig.rethisInstance) { "RedisRateLimit: rethisInstance must be provided!" }

    onCall { call ->
        val keyBase = pluginConfig.keySelector(call)
        val path = call.request.path()
        val redisKey = "ratelimit:$path:$keyBase"

        val results = rethis.transaction {
            incr(redisKey)
            expire(redisKey, pluginConfig.windowSeconds.seconds)
        }

        val count = (results?.first()?.value as Number).toLong()

        if (count > pluginConfig.maxRequests) {
            call.response.header(
                HttpHeaders.RetryAfter,
                pluginConfig.windowSeconds
            )

            pluginConfig.onRateLimited(call)

            return@onCall
        }
    }
}