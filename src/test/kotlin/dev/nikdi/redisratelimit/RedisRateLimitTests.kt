package dev.nikdi.redisratelimit

import com.redis.testcontainers.RedisContainer
import eu.vendeli.rethis.ReThis
import eu.vendeli.rethis.command.server.flushAll
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

internal class RedisRateLimitTests {
    companion object {
        private val redis = RedisContainer("redis:8.10.3-alpine3.23")
            .withExposedPorts(6379)
            .also { it.start() }

        private val rethis by lazy {
            ReThis(redis.host, redis.getMappedPort(6379))
        }
    }

    @BeforeTest
    fun flush(): Unit = runBlocking { rethis.flushAll() }

    private fun ApplicationTestBuilder.installRateLimit(
        maxRequests: Long = 10,
        windowSeconds: Long = 60,
    ) {
        application {
            routing {
                install(RedisRateLimit) {
                    this.rethisInstance = rethis
                    this.maxRequests = maxRequests
                    this.windowSeconds = windowSeconds
                }
                get("/test") { call.respond(HttpStatusCode.OK) }
            }
        }
    }

    @Test
    fun `missing rethisInstance throws with readable message`() {
        val ex = assertFailsWith<IllegalStateException> {
            testApplication {
                install(RedisRateLimit) { }
            }
        }

        assertEquals("RedisRateLimit: rethisInstance must be provided!", ex.message)
    }

    @Test
    fun `requests under the limit pass through`() = testApplication {
        installRateLimit(maxRequests = 5)
        val client = createClient {}
        repeat(5) {
            val response = client.get("/test")
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `request exactly at the limit still passes`() = testApplication {
        installRateLimit(maxRequests = 5)
        val client = createClient {}
        repeat(4) { client.get("/test") }
        val response = client.get("/test")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `request over the limit is blocked with 429`() = testApplication {
        installRateLimit(maxRequests = 5)
        val client = createClient {}
        repeat(5) { client.get("/test") }
        val response = client.get("/test")
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
    }

    @Test
    fun `blocked response includes RetryAfter header`() = testApplication {
        installRateLimit(maxRequests = 1, windowSeconds = 30)
        val client = createClient {}
        client.get("/test")
        val response = client.get("/test")
        assertEquals("30", response.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `different keys are tracked independently`() = testApplication {
        routing {
            install(RedisRateLimit) {
                rethisInstance = rethis
                maxRequests = 1
                keySelector = KeySelector("User ID") { call -> call.request.headers["X-User-ID"] ?: "anonymous" }
            }
            get("/test") { call.respond(HttpStatusCode.OK) }
        }

        val client = createClient {}
        client.get("/test") { headers.append("X-User-ID", "user-a") }
        val blockedA = client.get("/test") { headers.append("X-User-ID", "user-a") }
        val okB = client.get("/test") { headers.append("X-User-ID", "user-b") }

        assertEquals(HttpStatusCode.TooManyRequests, blockedA.status)
        assertEquals(HttpStatusCode.OK, okB.status)
    }

    @Test
    fun `custom onRateLimited handler is invoked`() {
        var handlerCalled = false
        testApplication {
            routing {
                install(RedisRateLimit) {
                    rethisInstance = rethis
                    maxRequests = 1
                    onRateLimited = { call ->
                        handlerCalled = true
                        call.respond(HttpStatusCode.ServiceUnavailable)
                    }
                }
                get("/test") { call.respond(HttpStatusCode.OK) }
            }

            val client = createClient {}
            client.get("/test")
            val response = client.get("/test")

            assertTrue(handlerCalled)
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }
    }

    @Test
    fun `window slides instead of resetting`() = testApplication {
        installRateLimit(maxRequests = 2, windowSeconds = 3)
        val client = createClient {}

        assertEquals(HttpStatusCode.OK, client.get("/test").status) // A at t=0
        delay(1500.milliseconds)
        assertEquals(HttpStatusCode.OK, client.get("/test").status) // B at t=1.5
        delay(1700.milliseconds) // t≈3.2: A expired, B still inside
        assertEquals(HttpStatusCode.OK, client.get("/test").status) // C: window holds only B
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/test").status) // D: B and C in window
    }

    @Test
    fun `client recovers after the window passes`() = testApplication {
        installRateLimit(maxRequests = 1, windowSeconds = 1)
        val client = createClient {}

        client.get("/test")
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/test").status)
        delay(1200.milliseconds)
        assertEquals(HttpStatusCode.OK, client.get("/test").status)
    }

    @Test
    fun `blocked request never reaches the route handler`() = testApplication {
        val reached = AtomicInteger(0)
        routing {
            install(RedisRateLimit) { rethisInstance = rethis; maxRequests = 1 }
            get("/test") { reached.incrementAndGet(); call.respond(HttpStatusCode.OK) }
        }
        val client = createClient {}
        repeat(3) { client.get("/test") }
        assertEquals(1, reached.get())
    }

    @Test
    fun `limits are tracked per path`() = testApplication {
        routing {
            install(RedisRateLimit) { rethisInstance = rethis; maxRequests = 1 }
            get("/a") { call.respond(HttpStatusCode.OK) }
            get("/b") { call.respond(HttpStatusCode.OK) }
        }
        val client = createClient {}
        client.get("/a")
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/a").status)
        assertEquals(HttpStatusCode.OK, client.get("/b").status)
    }

    @Test
    fun `concurrent requests never exceed the limit`() = testApplication {
        installRateLimit(maxRequests = 10)
        val client = createClient {}

        val statuses = coroutineScope {
            (1..50).map { async { client.get("/test").status } }.awaitAll()
        }

        assertEquals(10, statuses.count { it == HttpStatusCode.OK })
        assertEquals(40, statuses.count { it == HttpStatusCode.TooManyRequests })
    }
}