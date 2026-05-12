package dev.nikdi.redisratelimit

import eu.vendeli.rethis.ReThis
import eu.vendeli.rethis.command.generic.expire
import eu.vendeli.rethis.command.string.incr
import eu.vendeli.rethis.shared.types.Int64
import eu.vendeli.rethis.shared.types.RPrimitive
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.logging.*
import kotlin.time.Duration.Companion.seconds

private val logger = KtorSimpleLogger("dev.nikdi.redisratelimit.RedisRateLimit")

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
    val rethis = pluginConfig.rethisInstance ?: run {
        val msg = "RedisRateLimit: rethisInstance must be provided!"
        logger.error(msg)
        error(msg)
    }

    logger.debug("RedisRateLimit: Plugin initialized with config:\n{}", pluginConfig)

    onCall { call ->
        val keyBase = pluginConfig.keySelector(call)
        val path = call.request.path()
        val redisKey = "ratelimit:$path:$keyBase"

        val results = rethis.transaction {
            incr(redisKey)
            expire(redisKey, pluginConfig.windowSeconds.seconds)
        }

        val count = when (val response = results?.first()) {
            is Int64 -> response.value
            is RPrimitive -> (response.value as? Number)?.toLong()
                ?: error("RedisRateLimit: INCR returned non-numeric primitive: ${response::class.simpleName}")

            null -> error("RedisRateLimit: transaction returned no results")
            else -> error("RedisRateLimit: unexpected INCR response type: ${response::class.simpleName}")
        }

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