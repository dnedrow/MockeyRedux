plugins {
    id("mockey.application")
    // What the tests of another module need to run a server beside them, see ServerUnderTest.
    `java-test-fixtures`
}

application {
    mainClass = "io.github.dnedrow.mockey.server.MainKt"
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

// The test fixtures are a part of the server that only tests use, so they use what the server uses.
configurations.testFixturesImplementation {
    extendsFrom(configurations.implementation.get())
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

    // The tests of the distribution start the program from what the build delivers.
    val distribution = tasks.installDist.map { it.destinationDir }
    val runnableJar = tasks.named<Jar>("runnableJar").flatMap { it.archiveFile }
    inputs.files(tasks.installDist).withPropertyName("distribution").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(runnableJar).withPropertyName("runnableJar").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dmockey.distribution=${distribution.get()}", "-Dmockey.runnableJar=${runnableJar.get().asFile}")
        },
    )

    // The spike test of the HTTP stack sends the request targets of the recorded routing cases, which are
    // files of the parity module.
    inputs
        .files(fileTree("../mockey-parity-tests/src/test/resources/parity") { include("routing-*/*request.properties") })
        .withPropertyName("recordedRoutingRequests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
