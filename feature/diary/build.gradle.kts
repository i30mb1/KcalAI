plugins {
    id("n7.kcalai.kmp-compose")
}

kotlin {
    android {
        namespace = "n7.kcalai.feature.diary"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:personal"))
            api(project(":core:repositories"))
            api(project(":core:resolver"))
            // Мозг персонажей: их сообщения стоят в ленте, а вопросы и облачка — его ответы.
            api(project(":core:mascot"))
            api(project(":core:ui"))
            // Сканер — оверлей поверх дневника, а не отдельный экран: точка входа в него
            // живёт в строке ввода, и разводить их по разным местам незачем.
            api(project(":feature:scanner"))

            implementation(libs.cmp.material3)
            implementation(libs.cmp.material.icons.extended)
            implementation(libs.cmp.lifecycle.viewmodel.compose)
            implementation(libs.cmp.lifecycle.runtime.compose)
            implementation(libs.cmp.ui.backhandler)
        }
        commonTest.dependencies {
            // Экранная модель живёт на viewModelScope, то есть на главном диспетчере:
            // в тестах его подменяет тестовый, со своим виртуальным временем.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
