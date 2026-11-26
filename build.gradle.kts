plugins {
    // What finds the Java runtime for the end-to-end check, as the modules find the one that they build with.
    `jvm-toolchains`
}

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
                    // A module with test fixtures depends on itself: its fixtures use its main code, and its
                    // tests use its fixtures. That is no dependency between modules.
                    if (dependency is ProjectDependency && dependency.path != ":$module") {
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

// The Java runtime that the checks of the whole build start the programs with. The start scripts look for
// it in JAVA_HOME first.
val javaHomeOfTheBuild =
    javaToolchains
        .launcherFor { languageVersion = JavaLanguageVersion.of(21) }
        .map { it.metadata.installationPath.asFile.absolutePath }

// The end-to-end check runs the server and the UI together, each from the archive that the build delivers.
// The tests of a module start one program, and what stands in for the other one.
val endToEndCheck = tasks.register<Exec>("endToEndCheck") {
    group = "verification"
    description = "Starts the server and the UI from their distributions and checks that they work together."
    val version = project.version.toString()
    val serverDistribution = layout.projectDirectory.file("mockey-server/build/distributions/mockey-server-$version.tar.gz")
    val uiDistribution = layout.projectDirectory.file("mockey-ui/build/distributions/mockey-ui-$version.tar.gz")
    val script = layout.projectDirectory.file("scripts/end-to-end-check.sh")
    val passed = layout.buildDirectory.file("end-to-end-check/passed.txt")
    val javaHome = javaHomeOfTheBuild
    dependsOn(":mockey-server:distTar", ":mockey-ui:distTar")
    inputs.files(serverDistribution, uiDistribution).withPropertyName("distributions").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(script).withPropertyName("script").withPathSensitivity(PathSensitivity.NONE)
    // The document of Mockey 2.x that the script imports.
    inputs
        .file("mockey-legacy/src/test/resources/legacy/sample.xml")
        .withPropertyName("document")
        .withPathSensitivity(PathSensitivity.NONE)
    // The check is done for the distributions that it has passed with, until one of them changes.
    outputs.file(passed).withPropertyName("passed")
    // The script is a shell script, and it starts the programs with their start scripts for Unix.
    onlyIf("the system has a shell for the script") { !System.getProperty("os.name").startsWith("Windows") }
    commandLine("bash", script.asFile.path, serverDistribution.asFile.path, uiDistribution.asFile.path)
    doFirst {
        environment("JAVA_HOME", javaHome.get())
    }
    doLast {
        passed.get().asFile.writeText("The end-to-end check passed for the distributions of the version $version.\n")
    }
}

// A release is the files of the two programs, the release notes with the deviation register, and the
// checksums of the files. The release notes are those of the release that the version leads to: the notes
// of 3.0.0 for the version 3.0.0-rc.1. The workflow .github/workflows/release.yml publishes the directory.
val releaseDirectory = layout.buildDirectory.dir("release")

val releaseFiles = tasks.register<Exec>("releaseFiles") {
    group = "distribution"
    description = "Collects the files of a release, with its release notes and their checksums."
    val version = project.version.toString()
    val notes = layout.projectDirectory.file("docs/release-notes/${version.substringBefore('-')}.md")
    val register = layout.projectDirectory.file("docs/deviations.md")
    val script = layout.projectDirectory.file("scripts/assemble-release.sh")
    val files =
        listOf("mockey-server", "mockey-ui").flatMap { program ->
            listOf(
                "$program/build/libs/$program-$version-runnable.jar",
                "$program/build/distributions/$program-$version.zip",
                "$program/build/distributions/$program-$version.tar.gz",
            ).map { layout.projectDirectory.file(it) }
        }
    val directory = releaseDirectory
    dependsOn(listOf(":mockey-server", ":mockey-ui").flatMap { listOf("$it:runnableJar", "$it:distZip", "$it:distTar") })
    inputs.files(files).withPropertyName("files").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(notes, register, script).withPropertyName("notes").withPathSensitivity(PathSensitivity.NONE)
    outputs.dir(directory).withPropertyName("release")
    onlyIf("the system has a shell for the script") { !System.getProperty("os.name").startsWith("Windows") }
    commandLine(listOf("bash", script.asFile.path, directory.get().asFile.path, notes.asFile.path) + files.map { it.asFile.path })
}

// The release check starts each program from each of its files. It keeps nothing, so it runs whenever it
// is asked for.
tasks.register<Exec>("releaseCheck") {
    group = "verification"
    description = "Checks that the files that the release notes list exist and start."
    val script = layout.projectDirectory.file("scripts/release-check.sh")
    val directory = releaseDirectory
    val javaHome = javaHomeOfTheBuild
    dependsOn(releaseFiles)
    onlyIf("the system has a shell for the script") { !System.getProperty("os.name").startsWith("Windows") }
    commandLine("bash", script.asFile.path, directory.get().asFile.path)
    doFirst {
        environment("JAVA_HOME", javaHome.get())
    }
}

// The quick-start check follows the quick start of the README in a container that has a Java runtime and
// nothing else that a build has. It needs a container tool, so it is no part of `check`. The property
// `quickStartImage` names another image than the one of the script.
tasks.register<Exec>("quickStartCheck") {
    group = "verification"
    description = "Follows the quick start of the README in a container that has only a Java 21 runtime."
    val version = project.version.toString()
    val serverJar = layout.projectDirectory.file("mockey-server/build/libs/mockey-server-$version-runnable.jar")
    val uiJar = layout.projectDirectory.file("mockey-ui/build/libs/mockey-ui-$version-runnable.jar")
    val script = layout.projectDirectory.file("scripts/quick-start-check.sh")
    val image = providers.gradleProperty("quickStartImage")
    dependsOn(":mockey-server:runnableJar", ":mockey-ui:runnableJar")
    commandLine(listOf("bash", script.asFile.path, serverJar.asFile.path, uiJar.asFile.path) + listOfNotNull(image.orNull))
}

tasks.register("check") {
    dependsOn("checkModuleDependencies")
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn(endToEndCheck)
}
