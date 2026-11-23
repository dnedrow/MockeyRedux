# Traceability of the legacy surface

This table maps the HTTP surface of legacy Mockey (commit `88e31ed`) to the new system. It covers every
servlet mapping of the legacy `web/WEB-INF/web.xml`, the other entries of that file, every parameter that
the legacy API documentation (`/service_api`) describes, and the startup options. "Legacy tests" maps the
test classes of legacy Mockey to their ports.

Each row ends in one of three places:

- A requirement of the specifications, written as the capability and the name of the requirement, such as
  `server-runtime`: Liveness endpoint.
- A parity case, by the name of its recording in `mockey-parity-tests/src/test/resources/parity`, or by the
  common start of several names, such as `automation-definitions-*`.
- A non-goal of the design.

A row that begins with **Open** has no place yet. "Open points" at the end lists them.

`scripts/check-traceability.sh <legacy-checkout>` checks that the first table has one row for every servlet
mapping of the legacy `web.xml`, and no row for a mapping that the file does not have.

Deviation IDs (DEV-nn) refer to `docs/deviations.md`. Unless the legacy shim is enabled, the new mock listener
answers 404 for every legacy path other than `/service/*` and `/status` (DEV-26). The legacy pages become pages
of `mockey-ui`, which talks to the server through the admin API on the admin listener.

## Servlet mappings

| Mapping | Legacy servlet | What legacy does there | New home |
|---|---|---|---|
| `/jsonschemavalidate` | `JsonSchemaValidateServlet` | Page and endpoint that check data against a JSON Schema, with draft-03 as an option. | `service-definitions`: Response schema report, Supported schema dialects (DEV-23). `admin-ui`: Coverage of management operations. |
| `/api/*` | `ServletContainer` with the resources of `com.mockey.api` | An unfinished REST API: it lists the services, and its update operations store nothing. | `admin-api`: Versioned base path and format, Service and scenario management. |
| `/status` | `StatusServlet` | Says that the server runs, since when, and where its definitions are. | `server-runtime`: Liveness endpoint (DEV-28). Parity: `automation-status`. |
| `/validateSyntax` | `SyntaxValidateServlet` | Checks that a text is a JSON Schema. No legacy page calls it. | **Open.** No requirement covers a check of the schema alone, and the design names only the sample loader as a non-goal. |
| `/servicestatschart` | `StatisticsForServiceHistoryHtml` | Page that charts the number of history entries of each service for a range of dates. | `request-history`: Per-service statistics. `admin-ui`: Coverage of management operations. |
| `/servicestats` | `StatisticsForServiceAjaxServlet` | The same numbers as JSON or CSV. | `request-history`: Per-service statistics. `admin-api`: History access. |
| `/loadjsonschemasamples` | `JsonSchemaLoadSamplesServlet` | Loads sample schemas into the validation page. | Non-goal: the JSON Schema sample loader. |
| `/requestinspectors` | `RequestInspectorAjaxServlet` | Lists the installed inspectors. | `request-inspection`: Listing installed inspectors. `admin-api`: System operations. Parity: `inspection-inspectors-listed`. |
| `/lastvisithelp` | `LastVisitTagHelperServlet` | Clears the last-visit time of a service, a scenario, or a plan. | `request-history`: Last-used times. `admin-api`: System operations. |
| `/taghelp` | `TagHelperServlet` | Switches the tag filter on and off, and removes a tag from a service, a scenario, a plan, or all of them. | `request-routing`: Tag filter on routing. `service-definitions`: Tag listing and removal across definitions. `admin-api`: Routing settings management. Parity: the group `routing-tagged` sets its filter here. |
| `/viewme` | `FileViewServlet` | Serves a file of the Image Depot. | Non-goal: the Image Depot. |
| `/deletefile` | `FileDeleteServlet` | Deletes a file of the Image Depot. | Non-goal: the Image Depot. |
| `/uploadoctetstream` | `FileUploadOctetStreamReaderServlet` | Uploads a file to the Image Depot. | Non-goal: the Image Depot. |
| `/filesysteminfo` | `FileSystemManagerServlet` | Page that lists the files of the Image Depot. | Non-goal: the Image Depot. |
| `/console` | `ConsoleServlet` | Shows the debug log in the browser. | Non-goal: the in-browser debug log viewer. |
| `/definitions` | `ServiceDefinitionInfoServlet` | Lists services and their scenarios as JSON. | `legacy-compatibility`: Legacy definitions endpoint, Identifiers in legacy endpoints (DEV-20, DEV-47). Parity: `automation-definitions-*`. |
| `/search` | `SearchServlet` | Page that searches services, scenarios, and plans for a term. | `admin-api`: Search. `admin-ui`: Coverage of management operations. |
| `/configuration/reset_sticky_cookie_session` | `StickyCookieSessionAjaxServlet` | Empties the cookie store that proxied requests share. | `upstream-proxying`: Shared upstream session. `admin-api`: Outbound proxy management. Parity: the recorder calls it before every case, and `proxy-session-*` show the store. |
| `/twisting/delete` | `TwistInfoDeleteServlet` | Deletes a twist configuration. | `request-routing`: Twist configurations. `admin-api`: Routing settings management. |
| `/twisting/toggle` | `TwistInfoToggleServlet` | Enables or disables a twist configuration. | `request-routing`: Twist configurations, URL twisting before lookup. `admin-api`: Routing settings management. Parity: the group `routing-twist` enables its configuration here. |
| `/twisting/setup` | `TwistInfoSetupServlet` | Page and endpoint that create and change twist configurations. | `request-routing`: Twist configurations. `admin-api`: Routing settings management. |
| `/service_api` | `ApiDocumentationServlet` | Page that documents the four automation endpoints. | `admin-api`: API description document. The documented parameters are in the tables below. |
| `/conversation/record` | `HistoryAjaxServlet` | One history entry as JSON. | `request-history`: Recording handled requests. `admin-api`: History access. |
| `/conversation/record/latest` | `LatestHistoryAjaxServlet` | The latest history entry that passes the filter tokens, as JSON. | `request-history`: Listing history, Filtering history by tokens. `admin-api`: History access. Parity: `inspection-latest-entry`. |
| `/view/scenario` | `ScenarioViewAjaxServlet` | One scenario as JSON. | `admin-api`: Service and scenario management. |
| `/merge` | `ServiceMergeServlet` | Merges services into another service. | `service-definitions`: Service merge, Merge removes sources only when nothing was skipped (DEV-29). `admin-api`: Service and scenario management. |
| `/configuration/info` | `ConfigurationInfoAJAXServlet` | Reports the read-only mode, the outbound proxy flag, and the enabled twist configuration, and switches the read-only mode. | `admin-api`: System status, System operations. `definition-storage`: Switching read-only mode while running. |
| `/doitagain` | `RedoRequestServlet` | Sends the request of a history entry again, with changes. | `request-history`: Replaying an entry. `admin-api`: History access. |
| `/inspect` | `InspectFulfilledRequestServlet` | Serves the response body of a history entry under a content type that the caller names. | `admin-api`: History access. `admin-ui`: Stored content is shown as text. |
| `/inject` | `InjectRealUrlPerServiceServlet` | Adds real URLs to every service by a pattern and replacements. | `service-definitions`: Bulk derivation of real URLs. `admin-api`: Service and scenario management. |
| `/help` | `HelpServlet` | The help pages. | `admin-ui`: Built-in help. |
| `/service_scenario` | `ServiceConfigurationServlet` | A second path to `/config/service`, which the legacy pages use. | Not answered by the shim (DEV-26). Parity: `automation-config-second-path`. The pages use `admin-api`: Partial updates. |
| `/config/service` | `ServiceConfigurationServlet` | Changes the settings of a service. | `legacy-compatibility`: Legacy service configuration endpoint, Legacy service configuration result, Legacy result envelope. Parity: `automation-config-*`. |
| `/plan/setup` | `ServicePlanSetupServlet` | Saves, applies, and deletes plans, and sets the default plan. | `legacy-compatibility`: Legacy plan endpoint, Legacy plan result. `service-plans`: every requirement. Parity: `automation-plan-*`. |
| `/proxy/settings` | `ProxyInfoServlet` | Page and endpoint for the outbound proxy settings. | `upstream-proxying`: Outbound proxy, Outbound proxy settings and credentials. `admin-api`: Outbound proxy management. Parity: the group `proxy-outbound-credentials` sets its credentials here. |
| `/mobi` | `MobiServlet` | A page for small screens. | Non-goal: the mobile-only page. |
| `/home` | `HomeServlet` | The page of all services, and the initialization and reset of the definitions. | `legacy-compatibility`: Legacy initialization and reset endpoint, Legacy initialization is confined to the definitions location (DEV-27, DEV-49, DEV-50). `definition-storage`: Reset. `admin-ui`: Services overview. Parity: `automation-home-*`. |
| `/index.html` | `HomeServlet` | The same page. | `admin-ui`: Services overview. |
| `/history` | `HistoryServlet` | Page of the history with filter tokens, and the deletion and flagging of entries. | `request-history`: Listing history, Filtering history by tokens, Deleting history, Flagging entries. `admin-ui`: Live history view. Parity: `inspection-history-messages`. |
| `/fulfilledrequest` | `HistoryHtmlServlet` | One history entry as an HTML fragment. | `admin-api`: History access. `admin-ui`: Live history view. |
| `/scenariolist` | `ScenarioListServlet` | The scenarios of a service as an HTML fragment. | `admin-api`: Service and scenario management. `admin-ui`: Services overview. |
| `/setup` | `ServiceSetupServlet` | Page and endpoint that create, change, delete, and duplicate a service. | `service-definitions`: Service attributes, Service name validation, Unique mock URL pattern, A real URL belongs to one service, Service deletion, Service duplication (DEV-19). `admin-api`: Service and scenario management. |
| `/scenario` | `ScenarioServlet` | Page and endpoint that create, change, and delete a scenario and designate error scenarios. | `service-definitions`: Scenario attributes, Scenario name validation, Rule sets are validated on save, Error scenario designation (DEV-18). `admin-api`: Service and scenario management. |
| `/service/*` | `ResponseServlet` | The mock endpoint. | `request-routing`, `scenario-responses`, `upstream-proxying`, and `request-inspection`: every requirement. `server-runtime`: Default headers on mock responses. Parity: `routing-*`, `response-*`, `proxy-*`, `rule-*`, `inspection-response-unaffected`. |
| `/upload/*` | `UploadConfigurationServlet` | Page and endpoint that import a definitions document from an uploaded file or from a URL. | `definition-storage`: Import adds new services, Import merges services with the same name, Import of settings, plans, and twist configurations, Import report, Tagging imported definitions. `legacy-compatibility`: Import of a legacy single-file export, Legacy import follows the general import rules. `admin-api`: Definition exchange and diagnostics (DEV-27). |
| `/export/*` | `ExportConfigurationServlet` | The definitions as one XML document. | `definition-storage`: Export. `admin-api`: Definition exchange and diagnostics. |

## Other entries of web.xml

| Entry | What legacy does there | New home |
|---|---|---|
| Filter `EnableCorsFilter` on `/*` | Adds `Access-Control-Allow-Origin: *` and the headers against caching to every response. | `server-runtime`: Default headers on mock responses, Admin responses carry no cross-origin permission (DEV-26). Parity: every recording of the mock endpoint, whose headers are compared. |
| Welcome files `index.html`, `index.jsp`, `index.jws` | `/` shows the page of all services. | `admin-ui`: Services overview. |
| Error pages for 403, 404, and 500 | Pages of the user interface. An error inside a servlet shows a Java stack trace (`automation-config-unknown-service`, `response-text-body-percent`). | `admin-api`: Error responses. `legacy-compatibility`: Legacy result envelope (DEV-46). `scenario-responses`: Evaluation text for text criteria (DEV-36). `admin-ui`: Server unavailable. |

## Documented parameters

The legacy API documentation describes four endpoints. The shim answers them when it is enabled
(`legacy-compatibility`: The legacy shim is opt-in).

### `/config/service`

| Parameter | New home |
|---|---|
| `serviceId` | `legacy-compatibility`: Legacy service configuration endpoint, Identifiers in legacy endpoints (DEV-20). Parity: `automation-config-by-id`. |
| `serviceName` | `legacy-compatibility`: Legacy service configuration endpoint. Parity: `automation-config-scenario-by-name`, `automation-config-unknown-service` (DEV-46). |
| `serviceResponseType` with `proxy`, `static`, `dynamic` | `legacy-compatibility`: Legacy service configuration endpoint. Parity: `automation-config-proxy-type`, `automation-config-numeric-type`, which also shows the numbers 0 to 2 that the documentation leaves out. |
| `scenarioId` | `legacy-compatibility`: Legacy service configuration endpoint, Identifiers in legacy endpoints (DEV-20). Parity: `automation-config-by-id`. |
| Scenario name, documented under the name `serviceName` but read as `scenarioName` | `legacy-compatibility`: Legacy service configuration endpoint. Parity: `automation-config-scenario-by-name`, `automation-config-unknown-scenario`, `automation-config-effect`. |
| `hangTime` | `legacy-compatibility`: Legacy service configuration endpoint. Parity: `automation-config-scenario-by-name`, `automation-config-effect`. |
| `serviceSchema`, `serviceSchemaEnableFlag` | `legacy-compatibility`: Legacy service configuration endpoint. Parity: `automation-config-schema`. |
| `requestInspectorJsonRules`, `requestInspectorJsonRulesEnableFlag` | `legacy-compatibility`: Legacy service configuration endpoint. Parity: `automation-config-inspector`. |
| `transientState` | `legacy-compatibility`: Legacy service configuration endpoint, which accepts and ignores it (DEV-22). Parity: `automation-config-transient-state`. |
| Result: `success`, `fail`, `serviceId`, `serviceName` | `legacy-compatibility`: Legacy result envelope, Legacy service configuration result. Parity: `automation-config-*`. |

Legacy reads two more parameters that its documentation does not describe. Both are in
`legacy-compatibility`: Legacy service configuration endpoint: `requestInspectorName`
(`automation-config-inspector`) and `defaultUrlIndex` (`automation-config-default-url-index`).

### `/plan/setup`

| Parameter | New home |
|---|---|
| `action` with `save_plan` | `legacy-compatibility`: Legacy plan endpoint. `service-plans`: Saving a plan from current settings, Updating an existing plan. Parity: `automation-plan-save-*`, `automation-plan-saved-plan-applies`. |
| `action` with `set_plan` | `legacy-compatibility`: Legacy plan endpoint. `service-plans`: Applying a plan. Parity: `automation-plan-set-*` (DEV-50 for an unknown plan). |
| `action` with `delete_plan` | `legacy-compatibility`: Legacy plan endpoint. `service-plans`: Deleting a plan. Parity: `automation-plan-delete`, `automation-plan-delete-unknown`. |
| `action` with `set_as_default_plan` | `legacy-compatibility`: Legacy plan endpoint. `service-plans`: Default plan. Parity: `automation-plan-default`, `automation-plan-default-none`, `automation-plan-default-unknown`. |
| `plan_id` | `legacy-compatibility`: Legacy plan endpoint, Identifiers in legacy endpoints (DEV-20). Parity: `automation-plan-set-by-id`. |
| `service_plan_name` | `legacy-compatibility`: Legacy plan endpoint. Parity: `automation-plan-set-by-name`, `automation-plan-save-existing-name`. |
| `service_plan_tag` | `legacy-compatibility`: Legacy plan endpoint. Parity: `automation-plan-save-new`. |
| `type` with `json` | `legacy-compatibility`: Legacy result envelope: the result is always JSON (DEV-50). Parity: `automation-plan-save-new`. |
| `transient_state` | Accepted and ignored (DEV-22). Parity: `automation-plan-set-transient-state`. The requirement Legacy plan endpoint does not name the parameter, see "Open points". |
| Result: `success`, `fail`, `planId`, `planName` | `legacy-compatibility`: Legacy result envelope, Legacy plan result (DEV-48). Parity: `automation-plan-*`. |

Legacy reads one more parameter that its documentation does not describe: `service_ids[]`, the services of
a saved plan, which is in `legacy-compatibility`: Legacy plan endpoint (`automation-plan-save-*`).

### `/definitions`

| Parameter | New home |
|---|---|
| `serviceId` | `legacy-compatibility`: Legacy definitions endpoint, Identifiers in legacy endpoints (DEV-20). Parity: `automation-definitions-by-id`. |
| `serviceName` | `legacy-compatibility`: Legacy definitions endpoint. Parity: `automation-definitions-by-name`, `automation-definitions-unknown-name`. |
| Result: `serviceDefinitions` with `serviceId`, `serviceName`, `defaultScenarioId`, `defaultScenarioName`, `serviceResponseType`, `hangTime`, and `scenarioArray` with `scenarioId` and `scenarioName` | `legacy-compatibility`: Legacy definitions endpoint (DEV-47). Parity: `automation-definitions-all`, `automation-definitions-empty`. |

### `/home`

| Parameter | New home |
|---|---|
| `action` with `init` | `legacy-compatibility`: Legacy initialization and reset endpoint. Parity: `automation-home-init`, `automation-home-init-effect`, `automation-home-init-missing-file`. |
| `action` with `deleteAllServices` | `legacy-compatibility`: Legacy initialization and reset endpoint. `definition-storage`: Reset. Parity: `automation-home-delete-all`, `automation-definitions-empty`. |
| `type` with `json` | `legacy-compatibility`: Legacy result envelope: the result is always JSON (DEV-50). Parity: `automation-home-init-without-type`. |
| `transientState` | `legacy-compatibility`: Legacy initialization and reset endpoint. `definition-storage`: Switching read-only mode while running. Parity: every `automation-home-init*` case passes `true`. |
| Result: `success`, `fail` | `legacy-compatibility`: Legacy result envelope. Parity: `automation-home-*`. |

Legacy reads three more parameters that its documentation does not describe:

| Parameter | New home |
|---|---|
| `file` | `legacy-compatibility`: Legacy initialization and reset endpoint, Legacy initialization is confined to the definitions location (DEV-27). Parity: `automation-home-init`, `automation-home-init-not-a-document`. |
| `url` | `legacy-compatibility`: Legacy initialization is confined to the definitions location, which refuses it (DEV-27). Parity: `automation-home-init-url`, `automation-home-init-url-effect`. |
| `filterTag` | `legacy-compatibility`: Legacy initialization and reset endpoint (DEV-49). Parity: `automation-home-init-filter-tag`. |

## Startup options

| Option | What legacy does with it | New home |
|---|---|---|
| `-p`, `--port` | The port of the one listener, 8080 by default. | `server-runtime`: Two listeners, Settings from options and environment. |
| `-f`, `--file` | A definitions document to initialize from. | `server-runtime`: Initial definitions at startup, Initial definitions do not overwrite stored definitions (DEV-30). |
| `-u`, `--url` | The URL of a definitions document to initialize from. | `server-runtime`: Initial definitions at startup. |
| `-t`, `--transientState` | Read-only mode, on by default. | `definition-storage`: Read-only mode. |
| `-l`, `--location` | The directory of the stored definitions. | `server-runtime`: Definitions location setting. |
| `-F`, `--filterTag` | The tag filter. Legacy loses it when it loads an initialization document (DEV-49). | `server-runtime`: Settings from options and environment, whose scenario starts the server with a tag filter. |
| `-H`, `--headless` | Whether to leave the browser closed. | `server-runtime`: No other programs are started (DEV-25). The option goes away. |
| `-v`, `--version` | Prints the version. | `server-runtime`: Help and version output. |
| `--help` | Prints the options. | `server-runtime`: Help and version output. |

## Legacy tests

The 13 test classes under `src/test` of legacy Mockey are ported with the code that takes the place of what
they test (design decision 16). A ported case keeps the name of its legacy test method. A row names the test
class that holds the port, or the task that ports the class. `None` stands for a legacy class without a
test.

| Legacy test class | Port | Notes |
|---|---|---|
| `com.mockey.model.ScenarioTest` | `mockey-core`: `LegacyScenarioTest` | All five cases pass. `testdHeaderValue` reads a header of the typed list. Splitting the legacy header text is the importer's work (`legacy-compatibility`: Legacy response header text is split). |
| `com.mockey.model.ConflictInfoTest` | `mockey-core`: `LegacyConflictInfoTest` | Both cases pass. The definition diagnostics find the conflicts that the legacy class was given. Legacy also reported services with the same name, because their files collided; the new storage has no such collision (DEV-21), so equal names are not a diagnostic. |
| `com.mockey.SearchResultBuilderTest` | `mockey-core`: `LegacySearchResultBuilderTest` | All four cases pass. |
| `com.mockey.model.ServiceTest` | `mockey-core`: `LegacyServiceTest` | All five cases pass. Tags are added and taken away as sets, so `removeTagsFromService` takes away no tags where legacy took away `null`. `addRealUrlsToService` adds the real URLs with one update each. |
| `com.mockey.MockServiceBeanTest` | Task 4.1 | Both cases test the legacy `Url` class, as `UrlTest` does, and the code that takes its place is the target normalization. `parsesRealServiceUrlIntoHostAndPath` and the `wired.com` check of `parsesSchemeFromRealServiceUrl` read a text without a scheme as a host. They cannot pass: such a text is a relative target (DEV-03). |
| `com.mockey.storage.TestInMemoryMockeyStorage` | None | The class holds no test. The engine state holder takes the place of the legacy storage singleton, and `EngineStateTest` covers it. |
| `com.mockey.util.UrlTest` | Task 4.1 | |
| `com.mockey.model.UriTemplateTest` | Task 4.2 | |
| `com.mockey.plugin.TestPluginStore` | Task 5.4 | |
| `com.mockey.plugin.TestPackageInfoPeerClassFinder` | Task 5.4 | Its subject, the scan of the class path for annotated packages, no longer exists. |
| `com.mockey.model.ProxyServerModelTest` | Task 7.5 | |
| `com.mockey.util.ServiceStatHelperTest` | Task 8.6 | |
| `com.mockey.storage.xml.MockeyXmlFileManagerTest` | Task 10.2 | |

## Open points

- `/validateSyntax` has no place. It checks a JSON Schema by itself, and no legacy page uses it. Either the
  design names it as a non-goal next to the sample loader, or `service-definitions` gains a requirement for a
  schema that cannot be used. The response schema report of task 3.7 already says what is wrong with such a
  schema, although no requirement asks for it.
- `/setup` can also set the response mode of every service at once, with the parameters `all` and
  `responseType`. The legacy pages offer that as three buttons. No requirement describes one operation for
  all services: `admin-api`: Partial updates changes one service for each request.
- `legacy-compatibility`: Legacy plan endpoint does not say that `/plan/setup` accepts and ignores
  `transient_state`, as Legacy service configuration endpoint says for `transientState`. DEV-22 and the
  recording `automation-plan-set-transient-state` say so.
