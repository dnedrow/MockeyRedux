plugins {
    id("mockey.kotlin-jvm")
}

dependencies {
    implementation(project(":mockey-core"))
    implementation(project(":mockey-api"))
    implementation(project(":mockey-legacy"))
    // The core of Clikt alone: its full artifact adds a terminal library that loads native code.
    implementation(libs.clikt.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty) {
        // The native libraries of QUIC for five platforms, which only HTTP/3 loads. The server offers no
        // HTTP/3, and it runs on a Java runtime alone, see docs/decisions/http-stack.md.
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    implementation(libs.logback.classic)

    // The engine that the spike of task 11.0 compared with Netty. Only the spike test uses it.
    testImplementation(libs.ktor.server.cio)
}

tasks.processResources {
    // The program reads its version from a resource, which also works where no jar manifest is at hand.
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("**/server-version.properties") {
        expand("version" to version)
    }
}

tasks.test {
    // What the version resource has to hold.
    systemProperty("mockey.version", project.version.toString())

    // The spike test of the HTTP stack sends the request targets of the recorded routing cases, which are
    // files of the parity module.
    inputs
        .files(fileTree("../mockey-parity-tests/src/test/resources/parity") { include("routing-*/*request.properties") })
        .withPropertyName("recordedRoutingRequests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
