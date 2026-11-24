plugins {
    id("mockey.kotlin-jvm")
}

dependencies {
    implementation(project(":mockey-core"))

    testImplementation(libs.kotlinx.coroutines.core)
}
