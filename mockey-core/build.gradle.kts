plugins {
    id("mockey.kotlin-jvm")
    id("mockey.kotlin-serialization")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.kotlinx.serialization.core)
    testImplementation(libs.kotlinx.serialization.json)
}
