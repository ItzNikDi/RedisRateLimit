plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "dev.nikdi"
version = libs.versions.redis.rate.limit.get()

kotlin {
    jvmToolchain(21)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    compileOnly(libs.ktor.server.core)
    compileOnly(libs.rethis)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.rethis)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.redis)
}
