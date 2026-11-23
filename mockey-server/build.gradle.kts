plugins {
    id("mockey.kotlin-jvm")
}

dependencies {
    implementation(project(":mockey-core"))
    implementation(project(":mockey-api"))
    implementation(project(":mockey-legacy"))
}
