import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

// An application: a program that is delivered as a distribution with start scripts, and as a runnable jar,
// which is one file that `java -jar` runs. The module names its main class in the `application` block.

plugins {
    id("mockey.kotlin-jvm")
    application
}

/** What a task needs of Gradle when it runs. */
interface FileServices {
    @get:Inject
    val archives: ArchiveOperations

    @get:Inject
    val files: FileSystemOperations
}

// What a runnable jar must not take over from the jar of a library, beside the manifest, which Gradle leaves
// out by itself. An index describes the jar that it is in, and a signature holds for that jar alone. A module
// descriptor declares a module, which a jar of many libraries on the class path is not.
val notOfTheApplication =
    listOf(
        "META-INF/INDEX.LIST",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/*.EC",
        "module-info.class",
        "META-INF/versions/*/module-info.class",
    )

// Files that several libraries have under the same name, and that are lists to which each library adds its
// lines: the providers of a service, and the versions of the parts of Netty. The runnable jar has one file
// of each name with the lines of all of them.
val joinedOfTheLibraries = listOf("META-INF/services/**", "META-INF/io.netty.versions.properties")

// The jars of the libraries. The jars of the other modules are among them, and those are built before a
// task reads them: what a task takes from here says which tasks build it.
val libraries = configurations.runtimeClasspath.flatMap { it.elements }

val joinLibraryLists = tasks.register("joinLibraryLists") {
    description = "Joins the lists that several libraries of the application have under the same name."
    val jars = libraries
    val patterns = joinedOfTheLibraries
    val joined = layout.buildDirectory.dir("joined-library-lists")
    val services = objects.newInstance<FileServices>()
    inputs.files(jars).withPropertyName("libraries").withNormalizer(ClasspathNormalizer::class)
    inputs.property("patterns", patterns)
    outputs.dir(joined).withPropertyName("joined")
    doLast {
        val directory = joined.get().asFile
        services.files.delete { delete(directory) }
        jars.get().forEach { jar ->
            services.archives.zipTree(jar.asFile).matching { include(patterns) }.visit {
                if (!isDirectory) {
                    val target = directory.resolve(path)
                    target.parentFile.mkdirs()
                    // A list whose last line has no end would run into the first line of the next one.
                    target.appendText(file.readText().trimEnd() + "\n")
                }
            }
        }
    }
}

val runnableJar = tasks.register<Jar>("runnableJar") {
    group = "distribution"
    description = "Assembles a jar with the application and its libraries, which java -jar runs."
    archiveClassifier = "runnable"
    val archives = objects.newInstance<FileServices>().archives
    manifest {
        attributes(
            "Main-Class" to application.mainClass,
            // Some libraries hold classes for newer Java runtimes, which a runtime uses only when it is told to.
            "Multi-Release" to "true",
        )
    }
    from(sourceSets.main.map { it.output })
    from(joinLibraryLists)
    val leftOut = notOfTheApplication + joinedOfTheLibraries
    from(libraries.map { jars -> jars.map { archives.zipTree(it.asFile) } }) {
        exclude(leftOut)
    }
    // Gradle does not see by itself that the jar is out of date when what is left out of the libraries changes.
    inputs.property("leftOutOfTheLibraries", leftOut)
    // Two libraries with a file of the same name need a decision, which this script has to state.
    duplicatesStrategy = DuplicatesStrategy.FAIL
    includeEmptyDirs = false
}

tasks.assemble {
    dependsOn(runnableJar)
}

// The tests of an application run the commands of its document, which start the program on its default
// ports. Gradle runs the test tasks of several modules at the same time, and the server and the UI have the
// port of the admin listener in common. So the test task of an application holds the ports while it runs,
// and the test task of the other application waits for them.
val defaultPorts = gradle.sharedServices.registerIfAbsent("mockeyDefaultPorts", DefaultPorts::class) {
    maxParallelUsages = 1
}

tasks.withType<Test>().configureEach {
    usesService(defaultPorts)
}

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension = "tar.gz"
}

// Whoever gets the program gets its license with it.
val license = rootProject.layout.projectDirectory.file("LICENSE")

distributions.main {
    contents {
        from(license)
    }
}

runnableJar {
    from(license) {
        into("META-INF")
    }
}
