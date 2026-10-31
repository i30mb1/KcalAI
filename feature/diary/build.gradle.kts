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
            api(project(":core:ui"))
            // Сканер — оверлей поверх дневника, а не отдельный экран: точка входа в него
            // живёт в строке ввода, и разводить их по разным местам незачем.
            api(project(":feature:scanner"))

            implementation(libs.cmp.material3)
            implementation(libs.cmp.material.icons.extended)
            implementation(libs.cmp.lifecycle.viewmodel.compose)
            implementation(libs.cmp.lifecycle.runtime.compose)
        }
    }
}
