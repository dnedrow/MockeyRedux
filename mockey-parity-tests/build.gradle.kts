plugins {
    id("mockey.kotlin-jvm")
}

dependencies {
    testImplementation(project(":mockey-server"))
}

tasks.withType<Test>().configureEach {
    listOf("mockey.parity.legacy.baseUrl", "mockey.parity.recordGroup").forEach { name ->
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
}
