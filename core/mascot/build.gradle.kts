plugins {
    id("n7.kcalai.kmp-library")
}

kotlin {
    android {
        namespace = "n7.kcalai.mascot"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            // Сообщения и профиль персонажей лежат в общей базе, рядом с дневником.
            api(project(":core:database"))
            // Факты дня — из тех же моделей, что подсказывают еду: пропуск, остаток, раскладка.
            api(project(":core:personal"))
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
