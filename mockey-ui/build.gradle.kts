plugins {
    id("mockey.application")
}

application {
    mainClass = "io.github.dnedrow.mockey.ui.MainKt"
}

dependencies {
    // The UI is a client of the admin API, and of nothing else of the server: it sees the wire types and the
    // description of the API, and not the engine.
    implementation(project(":mockey-api"))
    // The core of Clikt alone: its full artifact adds a terminal library that loads native code.
    implementation(libs.clikt.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.html)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty) {
        // The native libraries of QUIC for five platforms, which only HTTP/3 loads. The UI offers no HTTP/3.
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    implementation(libs.logback.classic)

    // The tests read the pages as a browser does, and they hold the requests of the UI against the
    // description of the admin API.
    testImplementation(libs.jsoup)
    testImplementation(libs.json.schema)
}

tasks.processResources {
    // The program reads its version from a resource, which also works where no jar manifest is at hand.
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("**/ui-version.properties") {
        expand("version" to version)
    }
}

tasks.test {
    // What the version resource has to hold.
    systemProperty("mockey.version", project.version.toString())
}
