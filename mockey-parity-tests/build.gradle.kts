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
}
