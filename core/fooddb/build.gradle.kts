plugins {
    id("n7.kcalai.kmp-library")
}

kotlin {
    android {
        namespace = "n7.kcalai.fooddb"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(libs.androidx.sqlite)
            // FTS5 не гарантирован системным SQLite на minSdk 26 — нужен свой.
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}
