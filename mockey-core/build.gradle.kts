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

tasks.test {
    // The JDK HTTP client reads this property once, when it is first used, and by default it sends no Basic
    // credentials to a proxy for a tunnel. The tests of the upstream client need the same answer whichever of
    // them runs first, see docs/decisions/upstream-client.md.
    systemProperty("jdk.http.auth.tunneling.disabledSchemes", "")
}
