plugins {
    kotlin("jvm") version "2.3.21"
    application
}

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

val ktor = "3.2.3"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    // JSON через JsonElement API: компиляторный плагин сериализации не нужен.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.xerial:sqlite-jdbc:3.41.2.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    // Сертификат Let's Encrypt на внешний IP: сервер сам получает и продлевает его.
    implementation("org.shredzone.acme4j:acme4j-client:5.1.0")

    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
}

application { mainClass.set("n7.kcalai.server.MainKt") }

// Консоль Android Studio читает вывод как UTF-8, а JVM на Windows по умолчанию пишет в cp1251.
tasks.named<JavaExec>("run") {
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
