package dev.nikdi.redisratelimit

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * Returns the resolved client IP based on the proxy headers (if applicable).
 * Otherwise, returns the request's remote address.
 *
 * @see io.ktor.http.RequestConnectionPoint.remoteAddress
 */
fun ApplicationCall.clientIp(): String {
    val isBehindProxy = request.origin.remoteAddress.startsWith("172.") // loopback interface
            || request.origin.remoteAddress.startsWith("10.") // Cloudflare tunnel
            || request.origin.remoteAddress.startsWith("192.168") // local IPv4 network

    if (isBehindProxy) {
        request.headers["CF-Connecting-IP"]?.let { return it }
        request.headers["X-Forwarded-For"]?.let { return it }
    }

    return request.origin.remoteAddress
}