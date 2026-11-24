plugins {
    id("mockey.kotlin-jvm")
    id("mockey.kotlin-serialization")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.kotlinx.serialization.core)
    implementation(libs.json.schema)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // json-schema checks the `format` keyword with jmail, which it declares as optional.
    runtimeOnly(libs.jmail)
    testImplementation(libs.kotlinx.coroutines.test)
}
