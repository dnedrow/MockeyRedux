val allowedProjectDependencies = mapOf(
    "mockey-core" to emptySet(),
    "mockey-api" to emptySet(),
    "mockey-legacy" to setOf("mockey-core"),
    "mockey-server" to setOf("mockey-core", "mockey-api", "mockey-legacy"),
    "mockey-ui" to setOf("mockey-api"),
    "mockey-parity-tests" to setOf("mockey-server"),
)

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("checkModuleDependencies") {
    group = "verification"
    description = "Checks that project dependencies follow the modular architecture."
    val actualProjectDependencies = allowedProjectDependencies.keys.associateWith { module ->
        project(":$module").configurations
            .flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    if (dependency is ProjectDependency) {
                        dependency.path.removePrefix(":")
                    } else {
                        null
                    }
                }
            }
            .toSet()
    }
    inputs.property("allowedProjectDependencies", allowedProjectDependencies)
    inputs.property("actualProjectDependencies", actualProjectDependencies)
    doLast {
        val allowedDependencies =
            inputs.properties.getValue("allowedProjectDependencies") as? Map<*, *>
                ?: error("Missing allowed project dependencies")
        val actualDependencies =
            inputs.properties.getValue("actualProjectDependencies") as? Map<*, *>
                ?: error("Missing actual project dependencies")
        allowedDependencies.forEach { (module, allowed) ->
            val actual = actualDependencies[module]
            val allowedPaths = (allowed as? Iterable<*>)?.map { it.toString() }?.toSet().orEmpty()
            val actualPaths = (actual as? Iterable<*>)?.map { it.toString() }?.toSet().orEmpty()
            val forbidden = actualPaths - allowedPaths
            check(forbidden.isEmpty()) {
                "$module has forbidden project dependencies: ${forbidden.sorted().joinToString()}. " +
                    "Allowed dependencies: ${allowedPaths.sorted().joinToString().ifEmpty { "none" }}"
            }
        }
    }
}

tasks.register("printJavaVersion") {
    description = "Prints the Java version used by Gradle and the JAVA_HOME path."
    doLast {
        println("Java version used by Gradle: ${System.getProperty("java.version")}")
        println("JAVA_HOME: " + System.getProperty("java.home"))
    }
}

tasks.register("check") {
    dependsOn("checkModuleDependencies")
    dependsOn(subprojects.map { "${it.path}:check" })
}
