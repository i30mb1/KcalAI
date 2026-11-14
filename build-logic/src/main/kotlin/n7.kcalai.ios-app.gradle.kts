import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Оболочка приложения для iOS: те же цели, что у библиотек, но без Android
 * и с фреймворком на выходе.
 *
 * Отдельно от `n7.kcalai.kmp-library`: та объявляет цель `android` всегда,
 * а у оболочки Android-части нет и быть не может — на Android оболочка своя, `:app`.
 */
extensions.configure<KotlinMultiplatformExtension> {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            // Не `KcalApp`: так зовётся объект-точка входа, и в Swift имя модуля
            // затеняло бы его — `KcalApp.shared` перестал бы разрешаться.
            baseName = "Shared"
            // Статическим: динамический пришлось бы подписывать и вкладывать в бандл,
            // а наружу торчит один контроллер — делить тут нечего.
            isStatic = true
        }
    }

    sourceSets.all {
        // Та же отметка, что и у библиотек: время нужно почти везде, а в Kotlin 2.2
        // Instant и Clock ещё экспериментальные.
        languageSettings.optIn("kotlin.time.ExperimentalTime")
    }
}
