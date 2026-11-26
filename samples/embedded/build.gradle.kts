plugins {
    kotlin("jvm") version "2.4.21"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("io.github.dnedrow:mockey-core:3.0.0-rc.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

tasks.test {
    useJUnitPlatform()
}
