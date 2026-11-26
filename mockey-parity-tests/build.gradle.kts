plugins {
    id("mockey.kotlin-jvm")
}

dependencies {
    testImplementation(project(":mockey-server"))
    testImplementation(testFixtures(project(":mockey-server")))
}

tasks.withType<Test>().configureEach {
    listOf("mockey.parity.legacy.baseUrl", "mockey.parity.recordGroup", "mockey.parity.replayGroup").forEach { name ->
        System.getProperty(name)?.let { systemProperty(name, it) }
    }

    // The tests hold the deviation register and the traceability table against the recordings and against
    // the specifications, so they run again when one of those documents changes.
    inputs
        .files("../docs/deviations.md", "../docs/parity/traceability.md")
        .withPropertyName("parityDocuments")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .files(fileTree("../openspec") { include("**/specs/*/spec.md") })
        .withPropertyName("specifications")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
