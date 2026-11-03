plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.android.tools.build:gradle:9.3.2")
    // AGP 9.3.2 несёт Kotlin 2.2.10, а iOS-клибы Compose Multiplatform 1.12 собраны 2.3.20,
    // и Kotlin/Native 2.2 их не читает. Объявленная здесь версия выше встроенной и при разрешении classpath сборки
    // заменяет её — компилятор в сборке остаётся один, просто новее.
    // Номер обязан совпадать с kotlin в libs.versions.toml.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.21")
    // Compose Multiplatform 1.12: MeshGradientPainter в общем коде.
    implementation("org.jetbrains.compose:compose-gradle-plugin:1.12.2")
}
