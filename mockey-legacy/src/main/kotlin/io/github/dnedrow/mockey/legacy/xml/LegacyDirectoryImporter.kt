package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.Definitions
import java.nio.file.Files
import java.nio.file.Path

/** Imports the file-backed Mockey 2.x definitions layout without changing any source files. */
public class LegacyDirectoryImporter(
    private val root: Path,
) {
    private val normalizedRoot = root.toAbsolutePath().normalize()

    /** Imports [mainFile], or the conventional `mock_service_definitions.xml` when omitted. */
    public fun import(mainFile: Path = normalizedRoot.resolve("mock_service_definitions.xml")): LegacyDirectoryImportResult {
        val reader = LegacyXmlReader(normalizedRoot)
        val main = reader.read(mainFile)
        if (main.document == null) {
            return LegacyDirectoryImportResult(Definitions.EMPTY, main.issues)
        }

        val issues = main.issues.toMutableList()
        val services = main.document.documentElement.childElements("serviceref")
        val definitions =
            if (services.isEmpty()) {
                LegacyXmlDefinitions.map(main.document)
            } else {
                services
                    .mapIndexedNotNull { index, reference ->
                        val fileName = reference.getAttribute("file").takeUnless(String::isBlank)
                        if (fileName == null) {
                            issues += LegacyXmlIssue(mainFile, LegacyXmlIssueKind.UNREADABLE_FILE, "A service reference has no file.")
                            null
                        } else {
                            val file = normalizedRoot.resolve(fileName).normalize()
                            if (!file.startsWith(normalizedRoot)) {
                                issues +=
                                    LegacyXmlIssue(
                                        file,
                                        LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT,
                                        "A service reference points outside the import root.",
                                    )
                                null
                            } else {
                                val result = reader.read(file)
                                issues += result.issues
                                result.document?.let { document ->
                                    val responseFiles = responseFiles(document, file.parent, issues)
                                    LegacyXmlDefinitions.mapServiceFiles(document, responseFiles).services
                                }
                            }
                        }
                    }.flatten()
                    .let { Definitions(services = it) }
            }
        return LegacyDirectoryImportResult(definitions, issues)
    }

    private fun responseFiles(
        document: org.w3c.dom.Document,
        serviceDirectory: Path?,
        issues: MutableList<LegacyXmlIssue>,
    ): Map<String, String> {
        if (serviceDirectory == null) return emptyMap()
        val serviceElements =
            if (document.documentElement.localName == "service" || document.documentElement.nodeName == "service") {
                listOf(document.documentElement)
            } else {
                document.documentElement.childElements("service")
            }
        return serviceElements
            .flatMap { it.childElements("scenario") }
            .mapNotNull { scenario ->
                val response = scenario.childElement("scenario_response") ?: return@mapNotNull null
                val fileName =
                    response.getAttribute("file").takeUnless(String::isBlank)
                        ?: response.textContent.trim().takeIf { it.isNotEmpty() && !it.contains('\n') }
                        ?: return@mapNotNull null
                val file = serviceDirectory.resolve(fileName).normalize()
                if (!file.startsWith(normalizedRoot) || !Files.isRegularFile(file)) {
                    if (response.hasAttribute("file")) {
                        issues += LegacyXmlIssue(file, LegacyXmlIssueKind.UNREADABLE_FILE, "The scenario response file could not be read.")
                    }
                    return@mapNotNull null
                }
                try {
                    scenario.getAttribute("id") to Files.readString(file)
                } catch (e: Exception) {
                    issues +=
                        LegacyXmlIssue(
                            file,
                            LegacyXmlIssueKind.UNREADABLE_FILE,
                            e.message ?: "The scenario response file could not be read.",
                        )
                    null
                }
            }.toMap()
    }
}

public data class LegacyDirectoryImportResult(
    public val definitions: Definitions,
    public val issues: List<LegacyXmlIssue>,
)
