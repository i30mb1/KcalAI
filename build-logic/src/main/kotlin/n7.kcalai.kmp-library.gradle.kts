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
        compileSdk = 36
        minSdk = 26
        withHostTest {
            // Общий код пишет в журнал, а в заглушках android.jar Log.i бросает.
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
