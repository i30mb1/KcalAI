plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.android.tools.build:gradle:9.3.2")
    // Версия обязана совпадать со встроенным в AGP 9.3.2 Kotlin: два разных
    // компилятора в одном classpath сборки не уживаются.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10")
    // Compose Multiplatform 1.9 — линия под Kotlin 2.2.
    implementation("org.jetbrains.compose:compose-gradle-plugin:1.9.3")
}
