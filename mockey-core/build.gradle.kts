plugins {
    id("mockey.kotlin-jvm")
    id("mockey.kotlin-serialization")
    `maven-publish`
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

publishing {
    publications {
        create<MavenPublication>("mockeyCore") {
            from(components["java"])
        }
    }
}

// The libraries that hold an HTTP server or the API of one, each by the start of its coordinates. The core
// library works inside other programs, so it must do without all of them: an HTTP server comes with the
// server application only. Every library that builds on one of these brings it along, and so it is found too.
val httpServerLibraries =
    listOf(
        "io.ktor:ktor-server",
        "org.eclipse.jetty:jetty-server",
        "io.netty:netty-codec-http",
        "io.netty:netty-all",
        "org.apache.tomcat.embed:tomcat-embed-core",
        "org.apache.tomcat:tomcat-coyote",
        "io.undertow:undertow-core",
        "jakarta.servlet:jakarta.servlet-api",
        "javax.servlet:javax.servlet-api",
        "org.glassfish.grizzly:grizzly-http-server",
        "org.http4k:",
        "io.javalin:",
        "com.sparkjava:spark-core",
        "org.springframework:spring-webmvc",
        "org.springframework:spring-webflux",
        "io.vertx:vertx-core",
        "io.helidon.webserver:",
        "io.micronaut:micronaut-http-server",
        "org.nanohttpd:",
    )

val checkNoHttpServerDependency =
    tasks.register("checkNoHttpServerDependency") {
        group = "verification"
        description = "Checks that the core library depends on no HTTP server library."
        val runtimeModules =
            configurations.runtimeClasspath.map { classpath ->
                classpath.incoming.resolutionResult.allComponents
                    .mapNotNull { it.moduleVersion }
                    .map { "${it.group}:${it.name}" }
                    .sorted()
            }
        inputs.property("runtimeModules", runtimeModules)
        inputs.property("httpServerLibraries", httpServerLibraries)
        doLast {
            val modules = (inputs.properties.getValue("runtimeModules") as Iterable<*>).map { it.toString() }
            val servers = (inputs.properties.getValue("httpServerLibraries") as Iterable<*>).map { it.toString() }
            val forbidden = modules.filter { module -> servers.any { module.startsWith(it) } }
            check(forbidden.isEmpty()) {
                "mockey-core must work without an HTTP server, but its runtime class path holds ${forbidden.joinToString()}."
            }
        }
    }

tasks.check {
    dependsOn(checkNoHttpServerDependency)
}

tasks.test {
    // The JDK HTTP client reads this property once, when it is first used, and by default it sends no Basic
    // credentials to a proxy for a tunnel. The tests of the upstream client need the same answer whichever of
    // them runs first, see docs/decisions/upstream-client.md.
    systemProperty("jdk.http.auth.tunneling.disabledSchemes", "")
}
