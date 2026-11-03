plugins {
    kotlin("jvm") version "2.3.21"
    application
}

repositories {
    google()
    mavenCentral()
}

kotlin { jvmToolchain(21) }

dependencies {
    // Тот же движок, что считает отпечаток запроса на телефоне (litertlm-android).
    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.19.0")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
}

application { mainClass.set("n7.kcalai.embeddb.EmbedDbKt") }
