plugins {
    id("n7.kcalai.android-library")
}

android {
    namespace = "n7.kcalai.fooddb"
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.sqlite)
    // FTS5 не гарантирован системным SQLite на minSdk 26 — нужен свой.
    implementation(libs.androidx.sqlite.bundled)
}
