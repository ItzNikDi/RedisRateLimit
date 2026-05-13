package dev.nikdi.redisratelimit

import com.redis.testcontainers.RedisContainer
import eu.vendeli.rethis.ReThis
import eu.vendeli.rethis.command.server.flushAll
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.testcontainers.utility.DockerImageName
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class RedisRateLimitTests {
    companion object {
        private val redis = RedisContainer(DockerImageName.parse("redis:8.6.3-alpine"))
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

        assertEquals(ex.message, "RedisRateLimit: rethisInstance must be provided!")
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
                keySelector = { call -> call.request.headers["X-User-Id"] ?: "anonymous" }
            }
            get("/test") { call.respond(HttpStatusCode.OK) }
        }

        val client = createClient {}
        client.get("/test") { headers.append("X-User-Id", "user-a") }
        val blockedA = client.get("/test") { headers.append("X-User-Id", "user-a") }
        val okB = client.get("/test") { headers.append("X-User-Id", "user-b") }

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
}