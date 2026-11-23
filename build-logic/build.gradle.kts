plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation("org.jlleitschuh.gradle:ktlint-gradle:${libs.versions.ktlint.get()}")
}
