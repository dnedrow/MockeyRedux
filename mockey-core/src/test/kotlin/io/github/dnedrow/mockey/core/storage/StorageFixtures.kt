package io.github.dnedrow.mockey.core.storage

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** Definitions with every kind of definition and every setting, under identifiers that a test can name. */
internal object Sample {
    val gold =
        Scenario(
            id = ScenarioId("scn-gold"),
            name = "gold customer",
            responseBody = """{"tier":"gold"}""",
            statusCode = 201,
            headers = listOf(Header("Set-Cookie", "a=1"), Header("Content-Type", "application/json"), Header("Set-Cookie", "b=2")),
            matchCriterion = MatchCriterion.Text("ABC123"),
            methodRestriction = "POST",
            delayMillis = 500,
            tags = Tags.of("smoke regression"),
        )
    val byRules = Scenario(id = ScenarioId("scn-rules"), name = "By rules", matchCriterion = MatchCriterion.Rules("""{"url":[]}"""))
    val error = Scenario(id = ScenarioId("scn-error"), name = "error", responseBody = "Something went wrong.\n", statusCode = 500)
    val customers =
        Service(
            id = ServiceId("svc-customers"),
            name = "Customers",
            mockUrl = "customer/{id}",
            description = "Customer directory",
            realUrls = listOf("http://qa1.example.com/customers/{id}", "http://qa2.example.com/customers/{id}"),
            defaultRealUrlIndex = 1,
            responseMode = ResponseMode.DYNAMIC,
            delayMillis = 100,
            tags = Tags.of("crm"),
            scenarios = listOf(gold, byRules, error),
            defaultScenarioId = gold.id,
            errorScenarioId = error.id,
            requestRules = """{"headers":[]}""",
            requestRulesEnabled = true,
            inspectorName = "session-check",
            responseSchema = """{"type":"object"}""",
            responseSchemaEnabled = true,
        )
    val orders = Service(id = ServiceId("svc-orders"), name = "Orders", mockUrl = "http://api.example.com/orders")
    val plan =
        Plan(
            id = PlanId("plan-gold"),
            name = "Gold path",
            description = "Everything gold",
            tags = Tags.of("demo"),
            items = listOf(PlanItem("Customers", "gold customer", ResponseMode.STATIC, 250), PlanItem("Orders")),
        )
    val twist = TwistConfiguration(id = TwistId("twist-qa2"), name = "to-qa2", pairs = listOf(TwistPair("qa1", "qa2")))
    val settings =
        Settings(
            enabledTwistId = twist.id,
            outboundProxy = OutboundProxy("http://proxy.example.com:3128", enabled = true),
            universalErrorScenario = ScenarioReference(customers.id, error.id),
            defaultPlanId = plan.id,
        )
    val definitions = Definitions(services = listOf(customers, orders), plans = listOf(plan), twists = listOf(twist), settings = settings)

    /** The files of [definitions] in a location that held nothing before, by their paths from the location. */
    val files =
        listOf(
            "mockey.json",
            "services/customers/scenarios/by-rules.json",
            "services/customers/scenarios/error.body",
            "services/customers/scenarios/error.json",
            "services/customers/scenarios/gold-customer.body",
            "services/customers/scenarios/gold-customer.json",
            "services/customers/service.json",
            "services/orders/service.json",
        )
}

/**
 * Returns what is below [root]: every file by its path from [root], with `/` between the names, and with its
 * bytes as a text that has one character for each byte. A directory that holds nothing is there with a `/` at
 * the end of its path. Two locations hold the same bytes when this gives the same for both.
 */
internal fun treeOf(root: Path): Map<String, String> {
    val tree = sortedMapOf<String, String>()

    fun add(
        directory: Path,
        prefix: String,
    ) {
        val entries = directory.listDirectoryEntries()
        if (entries.isEmpty() && prefix.isNotEmpty()) tree[prefix] = ""
        for (entry in entries) {
            val path = prefix + entry.fileName
            if (entry.isDirectory()) add(entry, "$path/") else tree[path] = String(Files.readAllBytes(entry), Charsets.ISO_8859_1)
        }
    }
    if (root.isDirectory()) add(root, "")
    return tree
}

/** Returns the paths of the files below [root], see [treeOf], without the directories that hold nothing. */
internal fun filesOf(root: Path): List<String> = treeOf(root).keys.filterNot { it.endsWith("/") }

/** Returns the text of the file at [path] below [root], which is in UTF-8. */
internal fun textOf(
    root: Path,
    path: String,
): String = String(Files.readAllBytes(root.resolve(path)), Charsets.UTF_8)

/** Writes [text] in UTF-8 to the file at [path] below [root], and creates the directories that the file needs. */
internal fun write(
    root: Path,
    path: String,
    text: String,
) {
    val file = root.resolve(path)
    Files.createDirectories(file.parent)
    Files.write(file, text.toByteArray(Charsets.UTF_8))
}

/** Tells whether [path] below [root] is a file. */
internal fun isFile(
    root: Path,
    path: String,
): Boolean = root.resolve(path).isRegularFile()

/** Returns what a new storage loads from the location [root], as a program does that starts again. */
internal fun loaded(root: Path): Definitions = runBlocking { FileDefinitionStorage(root).load() }

/** Saves [definitions] to the location [root] with a new storage, which loads the location first as an engine does. */
internal fun stored(
    root: Path,
    definitions: Definitions,
) {
    runBlocking {
        val storage = FileDefinitionStorage(root)
        storage.load()
        storage.save(definitions)
    }
}

/**
 * Returns a storage for the location [root] that notes every step of its saves in [steps], as the kind of
 * the step and the path of its file from the location, such as `MOVE services/orders/service.json`.
 */
internal fun watchedStorage(
    root: Path,
    steps: MutableList<String>,
): FileDefinitionStorage = FileDefinitionStorage(root) { steps += "${it.kind} ${root.relativize(it.path).joinToString("/")}" }
