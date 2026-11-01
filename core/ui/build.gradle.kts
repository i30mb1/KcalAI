plugins {
    id("n7.kcalai.kmp-compose")
}

kotlin {
    android {
        namespace = "n7.kcalai.ui"
        // Шрифты лежат в Compose Resources, а на Android те едут как android-ресурсы.
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            // Всё здесь — api: модуль существует ровно затем, чтобы экраны получили
            // палитру, типографику и сам Compose одной зависимостью.
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.ui)
            api(libs.cmp.material3)
            // Кто такие Яйцо, Масло и Зёрнышко, знает модель; здесь — как они выглядят.
            api(project(":core:model"))
            implementation(libs.cmp.resources)
        }
        commonTest.dependencies {
            // Анимации куклы гоняются на виртуальном времени: без экрана и без ожидания.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    packageOfResClass = "n7.kcalai.ui.resources"
}
