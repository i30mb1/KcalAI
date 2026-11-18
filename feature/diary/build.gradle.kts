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
            // Нейросеть на телефоне: разбор фразы, которую не нашёл поиск, и вопросы к дневнику.
            api(project(":core:assistant"))
            // Мозг персонажей: их сообщения стоят в ленте, а вопросы и облачка — его ответы.
            api(project(":core:mascot"))
            // Телефон рядом: кому предложить еду и чем её отправить.
            api(project(":core:nearby"))
            // Очередь отправки: что считать отправленным — тем же кодом, что у фоновой отправки.
            api(project(":core:remote"))
            api(project(":core:ui"))
            // Сканер — оверлей поверх дневника, а не отдельный экран: точка входа в него
            // живёт в строке ввода, и разводить их по разным местам незачем.
            api(project(":feature:scanner"))

            implementation(libs.cmp.material3)
            implementation(libs.cmp.material.icons.extended)
            implementation(libs.cmp.lifecycle.viewmodel.compose)
            implementation(libs.cmp.lifecycle.runtime.compose)
            implementation(libs.cmp.navigationevent.compose)
        }
        commonTest.dependencies {
            // Экранная модель живёт на viewModelScope, то есть на главном диспетчере:
            // в тестах его подменяет тестовый, со своим виртуальным временем.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
