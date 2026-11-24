package io.github.dnedrow.mockey.legacy.xml

import java.nio.file.Path

/**
 * Imports the definitions of a definitions directory of Mockey 2.x: the directory in which Mockey 2.x in
 * writable mode stores its definitions.
 *
 * The main file of the directory, `mock_service_definitions.xml`, holds the plans, the twist configurations,
 * and the settings, and it refers to one file for each service. A service file includes one file for each
 * scenario, and a scenario file includes the text file that holds its response body. A main file can also
 * hold its services itself, as an export document does.
 *
 * Only files inside the directory [root] are read, see [LegacyXmlReader], and no file is ever written,
 * changed, or deleted: the directory stays as it is.
 */
public class LegacyDirectoryImporter(
    root: Path,
) {
    private val directory: Path = root.toAbsolutePath().normalize()

    /**
     * Imports the definitions with the main file [mainFile], which is `mock_service_definitions.xml` of the
     * directory unless another file of the directory is given. A relative path starts at the directory.
     *
     * A file that is missing or that cannot be read does not end the import. It is among the issues of the
     * result, and what the other files hold is imported.
     */
    public fun import(mainFile: Path = directory.resolve(MAIN_FILE)): LegacyImportResult {
        val file = directory.resolve(mainFile).normalize()
        val name = if (file.startsWith(directory)) directory.relativize(file) else file
        val reader = LegacyXmlReader(directory)
        return LegacyImport(reader).of(reader.read(file), "The file $name")
    }

    public companion object {
        /** The name of the main file of a definitions directory. */
        public const val MAIN_FILE: String = "mock_service_definitions.xml"
    }
}
