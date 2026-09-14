plugins {
    kotlin("jvm") version "2.3.10"
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

    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
}

application { mainClass.set("n7.kcalai.server.MainKt") }
