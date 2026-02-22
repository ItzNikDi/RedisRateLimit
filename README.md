# Redis Rate Limit

[![version](https://img.shields.io/github/v/release/ItzNikDi/RedisRateLimit?label=version)](https://github.com/ItzNikDi/RedisRateLimit/releases)

A **route-scoped** Ktor plugin that enforces rate limiting via _Redis_ as the backing store, powered by **[ReThis](https://github.com/vendelieu/re.this)**.

----

## Installation

----
0. Add the JitPack Maven repository:
```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
```

### A. Using the version catalog

----
1. Add the plugin to `libs.versions.toml`:

```toml
[versions]
redis-rate-limit = "0.1.0"

[libraries]
redis-rate-limit = { module = "com.github.ItzNikDi:RedisRateLimit", version.ref = "redis-rate-limit" }
```

2. Add the plugin to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.redis.rate.limit)
}
```

----
### B. Adding directly to `build.gradle.kts`
```kotlin
dependencies {
    implementation("com.github.ItzNikDi:RedisRateLimit:0.1.0")
}
```

## Quick Start

----
```kotlin
routing {
    route("/hello") {
        install(RedisRateLimit) {
            rethisInstance = ReThis(host = "localhost", port = 6379)
        }
        get { call.respond("Hello!") }
    }
}
```
This allows **_10 requests_ per _60 seconds_** per _client IP_ by **default**.

## Configuration

----
```kotlin
install(RedisRateLimit) {
    // a connected ReThis instance is required
    rethisInstance = ReThis("localhost", 6379)

    // maximum number of requests allowed within the window - defaults to 10
    maxRequests = 100

    // duration of the rate limit window in seconds - defaults to 60
    windowSeconds = 30

    // client identification - defaults to IP address
    keySelector = { call -> call.clientIp() }

    // limit exceeded behavior - defaults to 429 with no message
    onRateLimited = { call ->
        call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many requests"))
    }
}
```
----

## Credits to:

----
- [**vendelieu**](https://github.com/vendelieu) for creating [**Re.This**](https://github.com/vendelieu/re.this) - this project would **NOT** exist without it;
- the team behind [**Ktor**](https://github.com/ktorio/ktor);
----

### If you liked this project, ⭐ it, and report Issues in the dedicated tab :)
