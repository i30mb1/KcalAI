plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    compilerOptions {
        // expect/actual классы в Beta, но это штатный способ KMP, и без флага
        // каждое объявление сопровождается предупреждением.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    iosArm64()
    iosSimulatorArm64()

    android {
        compileSdk {
            version = release(36) {
                minorApiLevel = 1
            }
        }
        minSdk = 26
        withHostTest {
            // Общий код пишет в журнал, а в заглушках android.jar Log.i бросает.
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        // Instant и Clock из kotlin.time в Kotlin 2.2 ещё экспериментальные, а время
        // нужно почти везде. В 2.3 они стабильны, и отметку можно будет снять.
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
