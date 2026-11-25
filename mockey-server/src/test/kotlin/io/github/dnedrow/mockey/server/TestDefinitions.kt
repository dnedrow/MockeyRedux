package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/** Definitions for the tests of how the server starts, as an engine of a test defines them. */
internal object TestDefinitions {
    /** The answer of the service `Orders` of [orders] with its scenario `silver`, which is its default scenario. */
    const val SILVER_ORDERS = "silver orders"

    /** The answer of the service `Orders` of [orders] with its scenario `gold`, which its plan `Gold path` makes the default one. */
    const val GOLD_ORDERS = "gold orders"

    /**
     * Defines a service in static mode with the name [name] and the mock URL [mockUrl]. It has one scenario
     * for each of [answers], which are names with their response bodies, and the first is its default scenario.
     */
    suspend fun MockeyEngine.staticService(
        name: String,
        mockUrl: String,
        vararg answers: Pair<String, String>,
        tags: Tags = Tags.EMPTY,
    ): Service {
        val service =
            services
                .create {
                    it.copy(
                        name = name,
                        mockUrl = mockUrl,
                        responseMode = ResponseMode.STATIC,
                        tags = tags,
                    )
                }.getOrThrow()
        for ((scenario, body) in answers) scenarios.create(service.id) { it.copy(name = scenario, responseBody = body) }.getOrThrow()
        return service
    }

    /**
     * Defines the plan [name], which makes the scenario [scenarioName] the default scenario of the service
     * [serviceName], and designates it as the default plan. The plan is not applied.
     */
    suspend fun MockeyEngine.defaultPlan(
        name: String,
        serviceName: String,
        scenarioName: String,
    ) {
        val plan =
            plans
                .create {
                    it.copy(
                        name = name,
                        items = listOf(PlanItem(serviceName, scenarioName, ResponseMode.STATIC)),
                    )
                }.getOrThrow()
        plans.designateDefault(plan.id).getOrThrow()
    }

    /**
     * Defines the service `Orders` at `orders`, which answers with [SILVER_ORDERS]. With [withDefaultPlan],
     * the plan `Gold path` is the default plan, and a server that applies it answers with [GOLD_ORDERS].
     */
    suspend fun MockeyEngine.orders(withDefaultPlan: Boolean = false) {
        staticService("Orders", "orders", "silver" to SILVER_ORDERS, "gold" to GOLD_ORDERS)
        if (withDefaultPlan) defaultPlan("Gold path", "Orders", "gold")
    }

    /** Returns the definitions document of an engine that [define] gave its definitions: what an export of that engine gives. */
    fun document(define: suspend MockeyEngine.() -> Unit): String =
        runBlocking {
            MockeyEngine.create().use { engine ->
                engine.define()
                engine.exportDefinitions()
            }
        }

    /** Stores the definitions that [define] gives an engine in [directory], as a server in writable mode stores them. */
    fun store(
        directory: Path,
        define: suspend MockeyEngine.() -> Unit,
    ) {
        runBlocking {
            MockeyEngine
                .create {
                    storage = FileDefinitionStorage(directory)
                    readOnly = false
                }.use { it.define() }
        }
    }

    /**
     * Returns what [directory] holds, to compare it with what the directory holds later: every file and
     * directory under it by its path, with its content and with the time of its last change. A directory
     * that does not exist holds nothing.
     */
    fun contentOf(directory: Path): Map<String, String> {
        if (!Files.exists(directory)) return emptyMap()
        return Files.walk(directory).use { paths ->
            paths
                .sorted()
                .toList()
                .associate { path ->
                    val content =
                        if (Files.isDirectory(
                                path,
                            )
                        ) {
                            "a directory"
                        } else {
                            Files.readAllBytes(path).joinToString("") { "%02x".format(it) }
                        }
                    directory.relativize(path).toString() to "$content, changed at ${Files.getLastModifiedTime(path)}"
                }
        }
    }
}

/** Definitions of Mockey 2.x for the tests of how the server starts: an export document and a definitions directory. */
internal object LegacySources {
    /** The answer of the service `Legacy Orders` of [export] with its scenario `silver`, which is its default scenario. */
    const val SILVER_ORDERS = "silver orders of Mockey 2.x"

    /** The answer of the service `Legacy Orders` of [export] with its scenario `gold`, which its plan `Gold path` makes the default one. */
    const val GOLD_ORDERS = "gold orders of Mockey 2.x"

    /** The answer of the service `Directory Orders` of [directory]. */
    const val DIRECTORY_ORDERS = "orders of the directory of Mockey 2.x"

    private const val DECLARATION = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>"""
    private const val XINCLUDE = """xmlns:xi="http://www.w3.org/2001/XInclude""""

    /** The attributes of a service in static mode whose default scenario has the number 1, as Mockey 2.x writes them. */
    private const val STATIC_SERVICE =
        """default_real_url_index="0" default_scenario_id="1" description="" error_scenario_id="0" hang_time="0" """ +
            """request_inspector_name="null" service_response_type="1" tag="" """

    /**
     * Returns an export document of Mockey 2.x with the service `Legacy Orders` at `legacy/orders`, which
     * answers with [SILVER_ORDERS]. With [withDefaultPlan], its plan `Gold path` is the default plan, and a
     * server that applies it answers with [GOLD_ORDERS]. [serviceAttributes] are more attributes of the
     * service element.
     */
    fun export(
        withDefaultPlan: Boolean = false,
        serviceAttributes: String = "",
    ): String =
        """
        $DECLARATION
        <mockservice ${if (withDefaultPlan) """default_service_plan_id="1" """ else ""}version="1.0" xml:lang="en-US">
        <proxy_settings proxy_enabled="false" proxy_url=""/>
        <service name="Legacy Orders" url="legacy/orders" $STATIC_SERVICE $serviceAttributes>
        <request_inspector_json_rules enable_flag="false"/>
        <response_schema enable_flag="false"/>
        ${scenario(1, "silver", "<![CDATA[$SILVER_ORDERS]]>")}
        ${scenario(2, "gold", "<![CDATA[$GOLD_ORDERS]]>")}
        </service>
        <service_plan description="" id="1" name="Gold path" tag="">
        <plan_item hang_time="0" scenario_name="gold" service_name="Legacy Orders" service_response_type="1"/>
        </service_plan>
        </mockservice>
        """.trimIndent()

    private fun scenario(
        id: Int,
        name: String,
        response: String,
        attributes: String = "",
    ): String =
        """<scenario hang_time="0" http_method_type="" http_resp_status_code="200" id="$id" name="$name" tag="" $attributes>""" +
            """<scenario_match scenario_match_evaluation_rules_flag="false"/>""" +
            "<scenario_response>$response</scenario_response><scenario_response_header/></scenario>"

    /**
     * Writes a definitions directory of Mockey 2.x to [root], as Mockey 2.x in writable mode writes it: the
     * main file refers to the file of the service `Directory Orders` at `directory/orders`, which includes
     * the file of its scenario, which includes the text file of its response body, [DIRECTORY_ORDERS].
     * Returns [root].
     */
    fun directory(root: Path): Path {
        val service = "mockey_def_depot/directoryorders"
        write(
            root.resolve("mock_service_definitions.xml"),
            """
            $DECLARATION
            <mockservice version="1.0" xml:lang="en-US">
            <proxy_settings proxy_enabled="false" proxy_url=""/>
            <serviceref file="$service/directoryorders.xml"/>
            </mockservice>
            """.trimIndent(),
        )
        write(
            root.resolve("$service/directoryorders.xml"),
            """
            $DECLARATION
            <mockservice version="1.0" xml:lang="en-US">
            <service name="Directory Orders" url="directory/orders" $STATIC_SERVICE>
            <request_inspector_json_rules enable_flag="false"/>
            <response_schema enable_flag="false"/>
            <xi:include href="$service/scenarios/list.xml" parse="xml" $XINCLUDE/>
            </service>
            </mockservice>
            """.trimIndent(),
        )
        val body = """<xi:include href="list.txt" parse="text" $XINCLUDE/>"""
        write(
            root.resolve("$service/scenarios/list.xml"),
            DECLARATION + "\n" + scenario(1, "list", body, """version="1.0" xml:lang="en-US""""),
        )
        write(root.resolve("$service/scenarios/list.txt"), DIRECTORY_ORDERS)
        return root
    }

    private fun write(
        file: Path,
        text: String,
    ) {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
