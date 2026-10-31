plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.android.tools.build:gradle:9.3.2")
    // Версия обязана совпадать со встроенным в AGP 9.3.2 Kotlin: два разных
    // компилятора в одном classpath сборки не уживаются.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
}
