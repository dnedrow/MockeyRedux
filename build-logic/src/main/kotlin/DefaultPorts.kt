import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * The ports that the programs of this project use by default. The tests of an application start the program
 * on these ports, as its document does, and the server and the UI have the port of the admin listener in
 * common. A test task claims the ports by using this service, which only one task can use at a time.
 */
abstract class DefaultPorts : BuildService<BuildServiceParameters.None>
