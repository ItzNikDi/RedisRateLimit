package dev.nikdi.redisratelimit

import eu.vendeli.rethis.command.generic.expire
import eu.vendeli.rethis.command.sortedset.zAdd
import eu.vendeli.rethis.command.sortedset.zCard
import eu.vendeli.rethis.command.sortedset.zRemRangeByScore
import eu.vendeli.rethis.shared.response.sortedset.ZMember
import eu.vendeli.rethis.shared.types.Int64
import eu.vendeli.rethis.shared.types.RPrimitive
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.logging.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KtorSimpleLogger("dev.nikdi.redisratelimit.RedisRateLimit")

/**
 * A **route-scoped** Ktor plugin that enforces rate limiting via Redis as the backing store.
 *
 * Uses a **sliding window log**: each request is recorded in a Redis sorted set keyed by
 * route and client key (as determined by [RedisRateLimitConfig.keySelector]), scored by
 * timestamp. On every call, entries older than [RedisRateLimitConfig.windowSeconds] seconds
 * are discarded and the remaining entries are counted. When that count exceeds
 * [RedisRateLimitConfig.maxRequests], a `Retry-After` header is set, the
 * [RedisRateLimitConfig.onRateLimited] callback is invoked, and the request is not processed further.
 *
 * Redis keys follow the **`ratelimit:<path>:<key>`** pattern.
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
 * Notes:
 * - Memory per key grows with the number of requests in the window.
 * - Rejected requests are also recorded, so a client that keeps retrying stays blocked.
 * - Keys expire automatically after the window passes with no activity.
 * - Throws at startup if [RedisRateLimitConfig.rethisInstance] is not provided.
 * - If the Redis transaction fails, the request is let through and the error is logged.
 * 
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

        val nowMs = System.currentTimeMillis()
        val windowMs = pluginConfig.windowSeconds.seconds.inWholeMilliseconds
        val member = "$nowMs-${Uuid.random()}"

        val results = try {
            rethis.transaction {
                zRemRangeByScore(redisKey, 0.0, (nowMs - windowMs).toDouble()) // drop expired entries
                zAdd(redisKey, ZMember(member, nowMs.toDouble())) // record this request
                zCard(redisKey) // count in window
                expire(redisKey, pluginConfig.windowSeconds.seconds) // let idle keys vanish
            }
        } catch (e: Exception) {
            logger.error("RedisRateLimit: Redis call failed for {}", redisKey, e)
            return@onCall
        }

        val count = when (val response = results?.get(2) /* index 2 = ZCARD */) {
            is Int64 -> response.value
            is RPrimitive -> (response.value as? Number)?.toLong()
                ?: error("RedisRateLimit: ZCARD returned non-numeric primitive")

            null -> error("RedisRateLimit: transaction returned no results")
            else -> error("RedisRateLimit: unexpected ZCARD response type: ${response::class.simpleName}")
        }

        logger.trace("RedisRateLimit: {} count={}/{}", redisKey, count, pluginConfig.maxRequests)

        if (count > pluginConfig.maxRequests) {
            logger.debug(
                "RedisRateLimit: limit exceeded for {} (count={}, max={})",
                redisKey, count, pluginConfig.maxRequests
            )

            call.response.header(
                HttpHeaders.RetryAfter,
                pluginConfig.windowSeconds
            )

            pluginConfig.onRateLimited(call)

            return@onCall
        }
    }
}