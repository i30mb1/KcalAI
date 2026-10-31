plugins {
    id("n7.kcalai.kmp-library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    android {
        namespace = "n7.kcalai.database"

        // Обновление базы проверяется на устройстве: открыть файл прошлой версии
        // настоящим SQLite и убедиться, что дневник не стёрт молча.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(libs.androidx.room.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.sqlite.bundled)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.junit)
            implementation(libs.androidx.test.runner)
        }
    }
}

// ksp — расширение верхнего уровня, не часть блока kotlin
ksp {
    arg("room.generateKotlin", "true")
}

// Схема каждой версии — в git. Без схемы прошлой версии миграцию к следующей
// ни сгенерировать (AutoMigration), ни проверить.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Room генерирует реализацию базы под каждую цель отдельно.
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
