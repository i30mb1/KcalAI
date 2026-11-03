plugins {
    id("n7.kcalai.kmp-library")
    alias(libs.plugins.ksp)
}

kotlin {
    android {
        namespace = "n7.kcalai.assistant"
    }

    sourceSets {
        commonMain.dependencies {
            // Предложение модели — тот же ResolvedItem, что у поиска: чипс над строкой
            // ввода и запись в дневник не должны знать, кто его составил.
            api(project(":core:resolver"))
            api(project(":core:database"))
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // Gemini Nano через AICore. На iOS нейросети нет: экран живёт без неё.
            implementation(libs.mlkit.genai.agent)
            implementation(libs.mlkit.genai.prompt)
        }
        // Схему ответа ML Kit ищет через ServiceLoader, а файл службы KSP кладёт
        // в свои ресурсы, которые KMP-библиотека сама не упаковывает. Без него
        // чтение этикетки падает на телефоне — см. LabelFactsSchemaTest.
        androidMain {
            resources.srcDir(
                files(layout.buildDirectory.dir("generated/ksp/android/androidMain/resources"))
                    .builtBy("kspAndroidMain")
            )
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.mlkit.genai.prompt)
        }
    }
}

// Схема типизированного ответа модели (@Generable) собирается на этапе сборки.
dependencies {
    add("kspAndroid", libs.mlkit.genai.schema.compiler)
}
