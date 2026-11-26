package io.github.dnedrow.mockey.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 15.3: the traceability table has no row without a home, and every home that it names is there: the
 * requirements in the specifications, the parity cases among the recordings, and the deviations in the
 * register.
 */
class TraceabilityTest {
    @Test
    fun theTableMapsEveryRowToAHomeThatIsThere() {
        val problems =
            Traceability.problems(
                table = Path.of("../docs/parity/traceability.md"),
                specifications = Path.of("../openspec"),
                recordings = Path.of("src/test/resources/parity"),
                fixtures = Path.of("src/test/resources/parity-fixtures"),
                registeredDeviations = ParityReview.registeredDeviations(Path.of("../docs/deviations.md")),
            )

        assertEquals(emptyList(), problems)
    }

    /**
     * A row ends in requirements of a capability, in parity cases, or in a non-goal. A citation names one
     * requirement, several with commas between them, or every requirement, and what follows in lower case
     * is said about the requirement. A parity case is a recording, the start of the names of several
     * recordings, or a group of cases. The part of the table about the legacy tests is not checked.
     */
    @Test
    fun acceptsATableWhoseRowsEndInHomesThatAreThere() {
        val table =
            table(
                "A row ends in a requirement, such as `first-capability`: One requirement.",
                "",
                "| Mapping | New home |",
                "|---|---|",
                "| `/a` | `first-capability`: One requirement, Another requirement, which says more (DEV-01). |",
                "| `/b` | `first-capability`: A name, with commas, in it, Merged requirement. `second-capability`: every requirement. |",
                "| `/c` | Parity: `routing-one`, `proxy-*`, and the group `routing-group`. |",
                "| `/d` | Non-goal: the page that nobody uses. |",
                "| `/e \\| f` | Not answered (DEV-02). Parity: `routing-one`. |",
                "",
                "Text between two tables cites `second-capability`: Third requirement: `parameter` and `mockey-core`.",
                "",
                "## Legacy tests",
                "",
                "| Class | Port |",
                "|---|---|",
                "| `Old` | `mockey-core`: `New`. It cites `no-capability`: Nothing, `routing-nothing`, and DEV-99. |",
            )

        assertEquals(emptyList(), problemsOf(table))
    }

    @Test
    fun rejectsATableWithARowThatHasNoHomeOrNamesWhatIsNotThere() {
        val table =
            table(
                "| Mapping | New home |",
                "|---|---|",
                "| `/a \\| a2` | **Open.** Nothing yet. |",
                "| `/b` | Somewhere, some day (DEV-01). |",
                "| `/c` | `first-capability`: A name, with commas, in it, Unknown requirement. |",
                "| `/d` | `first-capability`: unknown text (DEV-02). |",
                "| `/e` | `third-capability`: One requirement. |",
                "| `/f` | Parity: `routing-nothing` and `proxy-z*`. |",
                "| `/g` | Non-goal: gone (DEV-99, DEV-99). |",
                "",
                "Text cites `second-capability`: One requirement.",
            )

        assertEquals(
            listOf(
                "line 3: the row `/a \\| a2` has no place yet",
                "line 4: the row `/b` ends in no requirement, no parity case, and no non-goal",
                "line 5: `first-capability` has no requirement 'Unknown requirement'",
                "line 6: `first-capability` has no requirement 'unknown text'",
                "line 7: the row `/e` ends in no requirement, no parity case, and no non-goal",
                "line 7: `third-capability` is no capability of the specifications",
                "line 8: `routing-nothing` is no recording, no start of the names of recordings, and no group of cases",
                "line 8: `proxy-z*` is no recording, no start of the names of recordings, and no group of cases",
                "line 9: DEV-99 is not in the deviation register",
                "line 11: `second-capability` has no requirement 'One requirement'",
            ),
            problemsOf(table),
        )
    }

    /** The requirements of a capability are the ones of every specification of it, of a change and of what it was merged into. */
    @Test
    fun readsTheRequirementsOfEveryCapability() {
        val ofTheFirst = setOf("One requirement", "Another requirement", "A name", "A name, with commas, in it", "Merged requirement")
        val expected = mapOf("first-capability" to ofTheFirst, "second-capability" to setOf("Third requirement"))

        assertEquals(expected, Traceability.requirementsIn(specifications()))
    }

    /** Writes a table with [lines] and returns it. */
    private fun table(vararg lines: String): Path {
        val table = Files.createTempFile("mockey-traceability-", ".md")
        Files.writeString(table, lines.joinToString("\n") + "\n")
        return table
    }

    /** Returns what is wrong with [table], for specifications, recordings, fixtures, and a register that this test writes. */
    private fun problemsOf(table: Path): List<String> {
        val work = Files.createTempDirectory("mockey-traceability-")
        val recordings = Files.createDirectory(work.resolve("parity"))
        listOf("routing-one", "proxy-a", "proxy-b").forEach { Files.createDirectory(recordings.resolve(it)) }
        Files.writeString(recordings.resolve("README.md"), "A file that is no recording.")
        val fixtures = Files.createDirectory(work.resolve("parity-fixtures"))
        Files.writeString(fixtures.resolve("routing-group.xml"), "<mockservice/>")
        return Traceability.problems(table, specifications(), recordings, fixtures, setOf("DEV-01", "DEV-02"))
    }

    /**
     * Writes the specifications of two capabilities and returns their root. One capability is specified in a
     * change and in what the change was merged into.
     */
    private fun specifications(): Path {
        val root = Files.createTempDirectory("mockey-specifications-")
        val change = "changes/some-change/specs"
        val first = root.resolve("$change/first-capability/spec.md")
        write(first, "One requirement", "Another requirement", "A name", "A name, with commas, in it")
        write(root.resolve("$change/second-capability/spec.md"), "Third requirement")
        write(root.resolve("specs/first-capability/spec.md"), "One requirement", "Merged requirement")
        Files.writeString(root.resolve("$change/first-capability/notes.md"), "### Requirement: No requirement\n")
        return root
    }

    private fun write(
        specification: Path,
        vararg requirements: String,
    ) {
        Files.createDirectories(specification.parent)
        val text = requirements.joinToString("\n\n", transform = ::requirement)
        Files.writeString(specification, "## ADDED Requirements\n\n$text\n")
    }

    /** Returns the text of a requirement with the name [name], as a specification writes it. */
    private fun requirement(name: String): String =
        "### Requirement: $name\nThe system SHALL do it.\n\n#### Scenario: It does\n- **THEN** it does"
}
