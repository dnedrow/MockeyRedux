package io.github.dnedrow.mockey.ui

import kotlinx.html.h2

/**
 * The topics of the built-in help, in the order of their list. They cover the help of Mockey 2.x topic by
 * topic, and they describe how this server behaves, which differs in places. `docs/decisions/ui-pages.md`
 * says which topic of Mockey 2.x went where.
 *
 * The texts are written for [prose]: blocks with an empty line between them, lists of lines that begin with
 * a hyphen, and backticks around what someone types or reads. They're texts of the UI, so they use no
 * contractions, like the other pages.
 */
internal object HelpTopics {
    /** The first thing that the page with the list of the topics says. */
    const val INTRODUCTION = """
        Mockey stands between a client and the services that the client calls. The client sends its requests
        to Mockey, and Mockey answers each one with a response that is defined for it, or it forwards the
        request to the real service and passes the answer on. So an application can be tested against answers
        that a real service gives rarely, slowly, or not at all.

        - A service stands for one real service. Clients call it at the mock endpoint of the server, at an
          address that begins with `/service/`.
        - A scenario is one response of a service: a status code, headers, and a body.
        - The response mode of a service says how the service answers: it forwards the request, it always
          answers with the same scenario, or it picks the scenario that fits the request.
        - The history records every request that the server handles, with its answer.

        This UI manages one server, which the footer of every page names. What the UI shows is what that
        server holds at the moment.
    """

    /** The last thing that the page with the list of the topics says. */
    const val MORE_TO_READ = """
        The documentation that comes with the sources of Mockey goes further than this help. `docs/ui.md`
        describes this UI, `docs/server.md` the server and its settings, `docs/rules.md` the request rules,
        and `docs/admin-api.md` the admin API, which programs use.
    """

    private val services =
        HelpTopic(
            name = "services",
            title = "Services",
            summary = "What a service is, how clients call it, and which service answers a request.",
            places = listOf("Services" to ServicesPages.OVERVIEW, "New service" to ServicePages.NEW),
            related = listOf("scenarios", "response-modes", "recording", "clients"),
        ) {
            prose(
                """
                A service stands for one real service that a client calls. It has a name, a mock URL pattern,
                the URLs of the real service, a response mode, a delay, tags, and its scenarios.
                """,
            )
            h2 { +"The mock URL pattern" }
            prose(
                """
                Clients call a service at the mock endpoint of the server: at `/service/`, followed by the
                mock URL pattern of the service. The overview shows the complete URL for each service.

                - A pattern such as `orders` stands for exactly that path.
                - A name in braces is a variable. In `customer/{id}`, the variable `id` stands for one part of
                  the path. So the pattern fits `customer/7` and `customer/abc`, and it does not fit
                  `customer/7/invoices`.
                - Letter case does not matter, and neither does a slash at the start or at the end.
                - A pattern belongs to one service. The server does not save a second service with the same
                  pattern.
                """,
            )
            h2 { +"Real URLs" }
            prose(
                """
                The real URLs of a service are the addresses of the real service that it stands for. One of
                them is the default one: in proxy mode, a request for the mock URL pattern is forwarded there.

                A client can also call a service by a real URL, at `/service/` followed by the complete URL,
                such as `/service/http://api.example.com/v1/orders`. A real URL can have variables, as a mock
                URL pattern can, and it belongs to one service.
                """,
            )
            h2 { +"Which service answers" }
            prose(
                """
                Where several patterns fit a request, the most specific one wins: the pattern with the fewest
                variables, then the one with the most fixed characters, and then the service that was created
                first.

                A request for a path that no service has gets the status 404. A request for a complete URL
                that no service lists is forwarded to that URL, and the server defines a service in proxy mode
                for it. That is how a service is recorded. A server that was started with
                `--no-forward-unknown-targets` answers such a request with the status 404 instead.
                """,
            )
            h2 { +"Finding a service" }
            prose(
                """
                The overview lists the services in the order of their names, and its filter limits the list to
                the services that carry certain tags. The search finds services by name, tag, mock URL
                pattern, and real URL, scenarios by name, tag, and body, and plans by name and tag.
                """,
            )
            h2 { +"The other settings" }
            prose(
                """
                - The delay is the time that the service waits before it answers, in milliseconds. A scenario
                  can have a delay of its own, which then replaces the delay of its service.
                - A service that is created without a name gets one from its first real URL, such as
                  `customers (auto generated)`.
                - The request rules, the inspector, and the response schema check what goes through the
                  service. They do not change how it answers.
                - Duplicating a service copies it with its scenarios, under a new name and a new mock URL
                  pattern, and without its real URLs.
                - Deleting a service deletes its scenarios.
                """,
            )
        }

    private val scenarios =
        HelpTopic(
            name = "scenarios",
            title = "Scenarios",
            summary = "What a scenario holds, and which scenario is the default one.",
            places = listOf("Services" to ServicesPages.OVERVIEW),
            related = listOf("response-modes", "match-criteria", "error-scenarios", "recording"),
        ) {
            prose(
                """
                A scenario is one response of a service: a status code, headers, and a body. A service can
                have any number of scenarios, and a service without a scenario forwards every request to the
                real service.

                - The server sends the body exactly as it is stored, in UTF-8 unless the `Content-Type` header
                  of the scenario names another charset.
                - The status code is 200 unless the scenario says otherwise. Other codes show how a client
                  handles a missing page, a redirect, or a failing service.
                - The headers are sent in their order, including several headers with the same name.
                - A scenario with a delay waits that long before it answers. Without one, the delay of the
                  service applies.
                - A scenario that is saved without a name gets a generated name.
                """,
            )
            h2 { +"The default scenario" }
            prose(
                """
                One scenario of a service is its default scenario. In static mode, the service answers every
                request with it. The first scenario of a service becomes its default scenario, and when the
                default scenario is deleted, the oldest of the others takes its place. Another scenario
                becomes the default one on the overview or on its own page.
                """,
            )
            h2 { +"For dynamic mode" }
            prose(
                """
                In dynamic mode, a service picks a scenario by the request. For that, a scenario has a match
                criterion, and it can have a method, which limits it to the requests with that method. A
                scenario without a match criterion answers no request in dynamic mode.
                """,
            )
            h2 { +"From a recorded answer" }
            prose(
                """
                The page of a history entry saves the answer of a real service as a scenario of its service.
                That is the quickest way to a scenario: let the server forward a request once, and keep the
                answer.
                """,
            )
        }

    private val responseModes =
        HelpTopic(
            name = "response-modes",
            title = "Response modes",
            summary = "How a service answers: by forwarding, with one scenario, or with the scenario that fits.",
            places = listOf("Services" to ServicesPages.OVERVIEW, "Outbound proxy and upstream session" to ProxyPages.PAGE),
            related = listOf("match-criteria", "error-scenarios", "services"),
        ) {
            prose("The response mode of a service says how it answers a request. The overview has a button for each mode.")
            rows(
                listOf("Mode", "What the service does"),
                listOf(
                    listOf("Proxy", "It forwards the request to the real service and passes the answer on."),
                    listOf("Static", "It answers with its default scenario, whatever the request contains."),
                    listOf("Dynamic", "It answers with the scenario whose match criterion fits the request best."),
                ),
            )
            h2 { +"Proxy mode" }
            prose(
                """
                A request for the mock URL pattern is forwarded to the default real URL of the service. A
                variable in that URL gets the value that the request has for the variable of the same name,
                and the query string of the request is appended. A request for a complete URL is forwarded to
                that URL. A service without a real URL answers a request for its mock URL pattern with the
                status 502.

                The server forwards the method, the body, and most headers as they are, and it keeps the
                cookies that real services set. Where the network needs it, the server forwards through an
                outbound proxy.
                """,
            )
            h2 { +"Static mode" }
            prose("The match criteria and the methods of the scenarios do not matter in static mode.")
            h2 { +"Dynamic mode" }
            prose(
                """
                Among the scenarios that match the request, the best match answers. When no scenario matches,
                the error scenario answers. Without an error scenario, the server answers with the status 404
                and a text that says so.
                """,
            )
            h2 { +"A service without a scenario" }
            prose(
                """
                A service without a scenario has nothing to answer with, so it forwards every request,
                whatever its mode is. The overview says which mode such a service answers in.
                """,
            )
        }

    private val matchCriteria =
        HelpTopic(
            name = "match-criteria",
            title = "Match criteria",
            summary = "How a service in dynamic mode picks the scenario for a request.",
            places = listOf("Services" to ServicesPages.OVERVIEW),
            related = listOf("request-rules", "scenarios", "error-scenarios"),
        ) {
            prose(
                """
                In dynamic mode, a service answers with the scenario that matches the request best. A scenario
                matches by its match criterion, which is a text or a rule set.
                """,
            )
            h2 { +"A text" }
            prose(
                """
                A scenario with a text matches a request that contains the text. Letter case does not matter.
                The server looks for the text in three places:

                - The values that the request has for the variables of the URL pattern.
                - The query parameters, each written as `name=value`.
                - The body of the request.

                The fixed parts of the path are not searched. For a service with the pattern `customer/{id}`,
                the text `customer` matches no request by its path, and the text `7` matches a request for
                `customer/7`.
                """,
            )
            h2 { +"A rule set" }
            prose(
                """
                A scenario with a rule set matches a request that satisfies the rule set. Rules look at a
                parameter, at a header, at the body, or at the URL, and they can ask for a regular expression.
                A scenario with this rule set matches a request for `customer?customer=111&invoice=222`:
                """,
            )
            example(
                """
                {
                  "parameters": [
                    {
                      "key": "customer",
                      "value_rule_arg": "111",
                      "value_rule_type": "string_required"
                    },
                    {
                      "key": "invoice",
                      "value_rule_arg": "222",
                      "value_rule_type": "string_required"
                    }
                  ]
                }
                """,
            )
            h2 { +"The method" }
            prose(
                """
                A scenario with a method matches only requests with that method. So one scenario can answer
                `PUT` and another one `DELETE` at the same URL. A scenario without a method matches requests
                of every method.
                """,
            )
            h2 { +"The best match" }
            prose(
                """
                Where several scenarios match, the one with the highest score answers:

                - The score of a text is its length. A longer text says more about the request.
                - The score of a rule set is the number of its rules that the request satisfies.
                - With equal scores, the scenario whose name comes first in the alphabet answers.
                """,
            )
            h2 { +"Beware of short texts" }
            prose(
                """
                Take a scenario A with the text `123`, a scenario B with the text `ABC`, and a request that
                contains `ABC123`. Both scenarios match, with the same score, so A answers because of its
                name. A scenario C with the text `ABC123` would answer in their place, because its text is
                longer.

                A text is found anywhere in the request, even inside a longer value. Where an exact value
                matters, use a rule set with the type `regex_required` and an expression such as `^123$`.
                """,
            )
        }

    private val requestRules =
        HelpTopic(
            name = "request-rules",
            title = "Request rules",
            summary = "The rule sets that inspect the requests of a service and that pick a scenario.",
            places =
                listOf("Services" to ServicesPages.OVERVIEW, "Diagnostics" to DiagnosticsPages.PATH, "History" to HistoryPages.LIST),
            related = listOf("match-criteria", "recording"),
        ) {
            prose(
                """
                A rule set says what a request must contain. It is a JSON text, and it is used in two places:

                - The request rules of a service inspect every request that the service gets, while they are
                  switched on. Every rule that a request fails gives a message in the history entry of the
                  request. The answer stays the same. So the rules reveal a client that sends less than the
                  real service needs.
                - The match criterion of a scenario can be a rule set. In dynamic mode, the scenario matches
                  the requests that satisfy it.
                """,
            )
            h2 { +"The format" }
            example(RULE_SET)
            prose("A rule set has up to four groups. Each group holds the rules for one part of the request:")
            rows(
                listOf("Group", "What its rules look at"),
                listOf(
                    listOf("`parameters`", "The parameters of the query string and the fields of a form."),
                    listOf("`headers`", "The headers of the request."),
                    listOf("`body`", "The body of the request, as text."),
                    listOf("`url`", "The URL that the client called, without its query string."),
                ),
            )
            prose("A rule has these members:")
            rows(
                listOf("Member", "Meaning"),
                listOf(
                    listOf("`key`", KEY_OF_A_RULE),
                    listOf("`value_rule_type`", "How the rule is evaluated, see the next table."),
                    listOf("`value_rule_arg`", "What the rule looks for: a text or a regular expression. It can be empty."),
                    listOf("`desc`", "A description, which is part of the message that a failed rule gives. It can be left out."),
                ),
            )
            rows(
                listOf("Type", "Satisfied when", "Without a value"),
                listOf(
                    listOf("`string_required`", STRING_REQUIRED, "Failed"),
                    listOf("`regex_required`", "The regular expression is found in a value.", "Failed"),
                    listOf("`regex_optional`", "The regular expression is found in a value.", "Satisfied"),
                ),
            )
            h2 { +"When a rule set is satisfied" }
            prose(
                """
                - A group is satisfied when the request satisfies every one of its rules.
                - A rule set is satisfied when one of its groups is satisfied. Only a group with a rule
                  counts.

                So the rules of a group are joined by "and", and the groups by "or". A rule set without a rule
                is never satisfied.
                """,
            )
            h2 { +"Regular expressions" }
            prose(
                """
                The expressions are Java regular expressions. An expression is searched for inside a value, so
                `\d+` is found in `order 66`, and `^\d+$` asks for a value of digits alone. Letter case
                matters unless the expression begins with `(?i)`. JSON needs every backslash twice: the
                expression `\d+` is written as `"\\d+"`.
                """,
            )
            h2 { +"Rule sets with a fault" }
            prose(
                """
                The server does not save a rule set that it cannot read, and the form says what is wrong with
                it. A rule set with a fault that came in with imported definitions is never satisfied, and
                the diagnostics report it.
                """,
            )
        }

    private val errorScenarios =
        HelpTopic(
            name = "error-scenarios",
            title = "Error scenarios",
            summary = "What a service answers when no scenario matches, or when the real service fails.",
            places = listOf("Services" to ServicesPages.OVERVIEW),
            related = listOf("scenarios", "response-modes", "match-criteria"),
        ) {
            prose(
                """
                An error scenario is the answer of a service that has no better one. That happens in two
                cases:

                - In dynamic mode, no scenario matches the request.
                - In proxy mode, the real service fails: it cannot be reached, it takes too long, its
                  certificate cannot be verified, it redirects without end, or its answer is incomplete.
                """,
            )
            h2 { +"The error scenario of a service" }
            prose("A service can have one of its scenarios as its error scenario. The page of a scenario has the button for it.")
            h2 { +"The universal error scenario" }
            prose(
                """
                One scenario of all services can be the universal error scenario. It answers for every service
                that has no error scenario of its own. So one answer is defined once, instead of an error
                scenario for every service.
                """,
            )
            h2 { +"Without an error scenario" }
            prose(
                """
                Without either of them, the server answers by itself: with the status 404 and a text that says
                that no scenario matched, or with the status 502 and a JSON object that describes the failure
                of the real service.
                """,
            )
            h2 { +"Use with care" }
            prose(
                """
                An error scenario that looks like a good answer hides real errors: the client gets valid data,
                and nobody notices that the real service failed or that no scenario matched. The best error
                scenario is one that is easy to tell from a good answer. The history says for every request
                whether an error scenario answered it.

                Deleting a scenario ends its role as an error scenario.
                """,
            )
        }

    private val plans =
        HelpTopic(
            name = "plans",
            title = "Plans",
            summary = "Snapshots of the settings of services, which put the services back into a state.",
            places = listOf("Plans" to PlanPages.LIST),
            related = listOf("response-modes", "scenarios", "storage"),
        ) {
            prose(
                """
                A plan is a snapshot of the settings of services. For each service, it holds the response
                mode, the delay, and the default scenario.

                Take an application that is tested as a gold member and as a silver member. Each test needs
                different answers from several services. With the services set for the gold member, save the
                plan "Gold member", and do the same for the silver member. From then on, applying a plan sets
                all those services at once.
                """,
            )
            h2 { +"Saving and applying" }
            prose(
                """
                - A new plan takes the settings of the services that are chosen for it, as they are at that
                  moment.
                - Saving into a plan again replaces what the plan holds with the current settings.
                - Applying a plan gives every service of the plan its response mode, its delay, and its default
                  scenario. The settings of other services stay as they are.
                - Deleting a plan changes no service.
                """,
            )
            h2 { +"What a plan refers to" }
            prose(
                """
                A plan refers to its services and scenarios by name. A service or a scenario that is
                renamed is renamed in the plans as well. One that is deleted stays in the plans, and applying
                such a plan leaves it out: the report of the plan lists the services that are gone and the
                scenarios that a service no longer has.
                """,
            )
            h2 { +"The default plan" }
            prose("One plan can be the default plan. The server applies it when it starts, after it has loaded its definitions.")
        }

    private val twisting =
        HelpTopic(
            name = "twisting",
            title = "Twisting",
            summary = "Rewriting the target of a request, so that requests for one address are answered as requests for another one.",
            places = listOf("Twisting" to TwistPages.LIST),
            related = listOf("services", "clients"),
        ) {
            prose(
                """
                Twisting rewrites the target of a request before the server looks for the service. The target
                is what follows `/service/` in the address. A twist configuration is a list of pairs, each with
                a text to look for and a text to put in its place.

                Twisting helps when a client cannot easily be pointed at another environment, and when the
                requests for one environment are to be answered as the requests for another one. It works in
                every response mode.
                """,
            )
            h2 { +"How it works" }
            prose(
                """
                - One twist configuration is enabled at a time, or none.
                - For each request, the server takes the first pair whose text is in the target, and replaces
                  the text wherever it occurs there. It applies no other pair.
                - Both texts are plain text, not regular expressions.
                """,
            )
            h2 { +"An example" }
            prose("""A twist configuration with the name "Everything goes to QA" has these pairs:""")
            rows(
                listOf("Look for", "Put in its place"),
                listOf(listOf("`uat1.example.com`", "`qa1.example.com`"), listOf("`uat2.example.com`", "`qa1.example.com`")),
            )
            prose(
                """
                A request for `/service/http://uat1.example.com/catalog/list` is handled as a request for
                `/service/http://qa1.example.com/catalog/list`: the service for that URL answers, or the request
                is forwarded there. The history entry of the request shows both targets.
                """,
            )
        }

    private val tags =
        HelpTopic(
            name = "tags",
            title = "Tags",
            summary = "Labels for services, scenarios, and plans, and the two filters that use them.",
            places = listOf("Tags" to TagPages.PAGE, "Services" to ServicesPages.OVERVIEW),
            related = listOf("services", "import"),
        ) {
            prose(
                """
                Services, scenarios, and plans carry tags. A tag is a word in lower case, and several tags are
                separated by spaces. Tags say what something is for, such as a team, a release, or a kind of client.
                """,
            )
            h2 { +"The filter of the overview" }
            prose(
                """
                The overview of the services can list only the services that carry certain tags. This filter
                is part of the address of the page. It changes what the page lists, and it does not change
                what the server does.
                """,
            )
            h2 { +"The tag filter of the server" }
            prose(
                """
                The tag filter of the server decides which services answer requests. While it is on, a service
                takes part only when the service or one of its scenarios carries one of the tags of the
                filter. The server treats every other service as if it were not there. A service that the
                server defines for an unknown URL gets the tags of the filter.

                The page for tags sets the filter and turns it off. The filter is not stored. A server starts
                with the filter of its setting `--tag-filter`, or without one.
                """,
            )
            h2 { +"Tags in bulk" }
            prose(
                """
                - The page for tags lists every tag in use, and it removes a tag from everything that carries
                  it.
                - An import can add tags to everything that it adds.
                """,
            )
        }

    private val storage =
        HelpTopic(
            name = "storage",
            title = "Storage",
            summary = "Where the definitions are kept, what read-only mode means, and what a reload and a reset do.",
            places = listOf("Server" to SystemPages.PAGE, "Import/Export" to DefinitionsPages.PAGE),
            related = listOf("import", "automation"),
        ) {
            prose(
                """
                The definitions determine how the server answers: the services with their scenarios, the
                plans, the twist configurations, and a few settings that belong to them. The server loads them from
                its definitions location when it starts. The page of the server names that location.
                """,
            )
            h2 { +"Read-only mode and writable mode" }
            prose(
                """
                - In read-only mode, every change stays in memory, and the server writes nothing to the
                  definitions location. A server starts in this mode unless it is told otherwise.
                - In writable mode, the server writes every change to the definitions location before it
                  confirms the change.

                Read-only mode suits a server whose definitions are files under version control: switching a
                response mode for a test changes no file. The price is that everything that was changed is
                gone when the server stops. To keep changes, switch to writable mode, which stores the
                definitions as they are at that moment, or download the definitions on the page for import
                and export.

                The footer of every page says which mode the server is in, and the page of the server
                switches the mode.
                """,
            )
            h2 { +"Reload" }
            prose("A reload makes the server read its definitions location again. Every change that is not stored is gone then.")
            h2 { +"Reset" }
            prose(
                """
                A reset removes all services, scenarios, plans, and twist configurations, the settings of the
                outbound proxy, the tag filter, and the history. In writable mode, it also empties the
                definitions location. The page that asks before a reset offers the download of the
                definitions.
                """,
            )
            h2 { +"What is never stored" }
            prose(
                """
                The history, the last-used times, the cookies of real services, the user name and the password
                of the outbound proxy, the tag filter, and the mode itself are held in memory only. A server
                that is restarted has none of them.
                """,
            )
        }

    private val import =
        HelpTopic(
            name = "import",
            title = "Import and export",
            summary = "Moving definitions between servers, and bringing in the definitions of Mockey 2.x.",
            places = listOf("Import/Export" to DefinitionsPages.PAGE),
            related = listOf("storage", "merging", "tags"),
        ) {
            prose("The page for import and export moves definitions between servers.")
            h2 { +"Export" }
            prose(
                """
                The export is one JSON document with everything that a server stores: the services with their
                scenarios, the plans, the twist configurations, and the settings that belong to them. It can be
                kept, shared, and imported into another server.
                """,
            )
            h2 { +"Import" }
            prose(
                """
                The import takes a document that is uploaded: a definitions document of this server, or an
                export document of Mockey 2.x, which is XML. It adds to what the server holds and replaces
                nothing:

                - A service with a new name is added, with its scenarios.
                - A service whose name is in use is merged into the service of that name. The import adds the
                  scenarios that the service does not have, and the real URLs and the tags of the document. A
                  scenario counts as present when the service has one with the same name and the same body.
                  Nothing that the service holds is changed.
                - A plan or a twist configuration with a new name is added. One whose name is in use is
                  reported as a conflict.
                - The settings of the outbound proxy, the universal error scenario, and the default plan are
                  taken only where the server has none.

                Every import ends with a report that lists each addition and each conflict. Tags that are
                entered with the import are added to every service, scenario, and plan that the import added
                or merged into.
                """,
            )
            h2 { +"Other sources" }
            prose(
                """
                A running server reads a document only from an upload. A file or a URL is a source when the
                server starts, in the setting `--initial-definitions`, and for the command `import-legacy` of
                the server, which adds the definitions of Mockey 2.x to a definitions location.
                """,
            )
        }

    private val recording =
        HelpTopic(
            name = "recording",
            title = "Recording and the history",
            summary = "Letting the server forward requests, and what the history offers for what it recorded.",
            places = listOf("History" to HistoryPages.LIST, "Statistics" to StatisticsPages.PAGE),
            related = listOf("services", "scenarios", "request-rules"),
        ) {
            prose(
                """
                The quickest way to a service is to let the server record it. Put the address of the mock
                endpoint in front of the URL of the real service, and call that:
                """,
            )
            example("http://localhost:8080/service/http://api.example.com/v1/orders")
            prose(
                """
                The server forwards the request and passes the answer on. It defines a service in proxy mode
                for the URL, which the overview lists from then on, and the history holds the request and the
                answer.
                """,
            )
            h2 { +"The history" }
            prose(
                """
                The history lists every request that the server handled, the newest first: who sent it, which
                service answered in which mode, and with what. The page of an entry shows the request and the
                answer in full, with the messages of the request rules and of the inspector.

                - Filter tokens limit the list to the entries that hold each of them. A token after `!` limits
                  it to the entries that do not hold the token.
                - A flag marks an entry.
                - A replay sends the request of an entry again, as it was or with changes.
                - Saving the answer of an entry as a scenario turns one recorded exchange into a mock: switch
                  the service to static mode, and it answers with that scenario.
                - With automatic refresh, new entries appear by themselves.
                - The statistics count the entries of each service.
                """,
            )
            h2 { +"What the history keeps" }
            prose(
                """
                The server keeps the newest entries in memory, 1,000 of them unless it is started with another
                limit. Of a long body, it keeps the first part, which is 1 MiB by default. The history is gone
                when the server stops.
                """,
            )
        }

    private val clients =
        HelpTopic(
            name = "clients",
            title = "Pointing a client at Mockey",
            summary = "How an application reaches its services through the server.",
            places = listOf("Services" to ServicesPages.OVERVIEW),
            related = listOf("services", "twisting", "recording"),
        ) {
            prose(
                """
                An application reaches a service through Mockey at the URL that the overview shows for the
                service. There are two ways to write that URL:

                - With the mock URL pattern of the service, as in `http://localhost:8080/service/orders`.
                - With the URL of the real service, as in
                  `http://localhost:8080/service/http://api.example.com/v1/orders`.
                """,
            )
            h2 { +"A base URL in the configuration" }
            prose(
                """
                The second way needs one change in an application that keeps the URLs of its services in its
                configuration: a base URL in front of each of them.
                """,
            )
            example(
                """
                BASE_URL=http://localhost:8080/service/
                ORDERS_URL=http://api.example.com/v1/orders
                CATALOG_URL=http://catalog.example.com/products
                """,
            )
            prose(
                """
                The application calls the base URL followed by the URL of the service. For production, the
                base URL is empty, and the application calls the services themselves.
                """,
            )
            h2 { +"A client that cannot be changed" }
            prose(
                """
                Where the URLs of a client cannot be changed, twisting lets the requests for one address be
                answered as requests for another one.
                """,
            )
            h2 { +"From a web page" }
            prose(
                """
                The server adds the header `Access-Control-Allow-Origin: *` to its answers, and headers that
                keep them out of caches, unless the scenario or the real service sets a header of the same
                name. So a page of another site can call a mocked service from a browser.
                """,
            )
        }

    private val merging =
        HelpTopic(
            name = "merging",
            title = "Merging services",
            summary = "Making one service of several that stand for the same real service.",
            places = listOf("Services" to ServicesPages.OVERVIEW),
            related = listOf("services", "real-urls", "import"),
        ) {
            prose(
                """
                Recording and importing can leave several services that stand for one real service, such as
                one for `http://example.com/authentication` and one for `http://example.com/login`. Merging
                makes one service of them.

                The page of a service merges other services into it:

                - Every scenario of the other services is copied, unless the service has a scenario with the
                  same name and the same body. Such a scenario is skipped.
                - The tags of the other services are added.
                - When no scenario was skipped, the real URLs of the other services move to the service, and
                  the other services are deleted. So the service answers for all their URLs.
                - When a scenario was skipped, the other services stay, with their real URLs.

                The report of the merge lists every scenario that was copied and every one that was skipped.
                The UI asks before it merges.
                """,
            )
        }

    private val realUrls =
        HelpTopic(
            name = "real-urls",
            title = "Real URLs for another environment",
            summary = "Adding the URLs of another environment to every service at once.",
            places = listOf("Derive real URLs" to RealUrlPages.PAGE),
            related = listOf("services", "twisting"),
        ) {
            prose(
                """
                Services are often defined with the real URLs of one environment, such as `qa1.example.com`.
                When the requests come for another environment, such as `qa8.example.com`, no service knows
                their URLs. Deriving real URLs adds the URLs of the other environment to every service at
                once. Mockey 2.x called this URL injection.

                The form takes a regular expression and one or more replacements. For every real URL of every
                service, and for every replacement, the server replaces what the expression finds, and adds
                the result to the real URLs of the service. It adds no URL that a service lists already.
                """,
            )
            h2 { +"An example" }
            prose("A service has these real URLs:")
            example(
                """
                http://qa1.example.com/authentication
                http://qa2.example.com/authentication
                """,
            )
            prose("""With the expression `qa1\.example\.com` and the replacement `qa8.example.com`, it has three:""")
            example(
                """
                http://qa1.example.com/authentication
                http://qa2.example.com/authentication
                http://qa8.example.com/authentication
                """,
            )
        }

    private val schema =
        HelpTopic(
            name = "schema",
            title = "Response schemas",
            summary = "Checking the bodies of the scenarios of a service against a JSON Schema.",
            places = listOf("Services" to ServicesPages.OVERVIEW, "Diagnostics" to DiagnosticsPages.PATH),
            related = listOf("scenarios", "request-rules"),
        ) {
            prose(
                """
                A service can have a response schema: a JSON Schema that says what a good answer of the
                service looks like. The page of the service checks its scenarios against the schema. The
                report says for every scenario whether its body conforms, and what is wrong where it does not.

                - A scenario that does not conform is saved and served like any other one. The schema reports,
                  and it prevents nothing.
                - While the schema is switched on for the service, the diagnostics report the scenarios that
                  do not conform.
                - The schema can be written in draft-04 of JSON Schema or in a later one. For a schema of a
                  dialect that the server does not know, the report says so and gives no verdict.
                """,
            )
        }

    private val automation =
        HelpTopic(
            name = "automation",
            title = "Automation and startup",
            summary = "The admin API for programs, scripts for Mockey 2.x, the status address, and the settings that a server starts with.",
            places = listOf("Server" to SystemPages.PAGE),
            related = listOf("storage", "plans"),
        ) {
            prose(
                """
                Everything that this UI does, it does through the admin API of the server. A program can use
                the same API: a test that puts the services into a state before it runs, or a script that
                applies a plan.

                - The admin API answers on the admin listener of the server, at `/api/v1`. That listener has a
                  port of its own, 8081 unless the server is told otherwise, and by default only programs on
                  the machine of the server reach it.
                - The API describes itself at `/api/v1/openapi.json`.
                - A server with an admin token takes only the requests that present the token.
                """,
            )
            h2 { +"The automation endpoints of Mockey 2.x" }
            prose(
                """
                Mockey 2.x had four addresses for scripts on the port of the mock traffic, its automation
                endpoints: `/definitions`, `/config/service`, `/plan/setup`, and `/home`. A server answers
                them only when it is started with the setting `--legacy-shim`. The page of the server says
                whether the legacy shim is enabled. The endpoints take the parameters of Mockey 2.x, so a
                script that was written for Mockey 2.x keeps working.

                The endpoints ask for no admin token. Whoever reaches the mock listener of a server with the
                legacy shim can change its definitions and delete them. A script that is written today uses
                the admin API.
                """,
            )
            h2 { +"Whether the server runs" }
            prose(
                """
                The address `/status` on the mock listener says that the server runs and since when. A build
                server can ask it before the tests begin.
                """,
            )
            h2 { +"Starting the server" }
            prose(
                """
                The command `mockey-server --help` prints every setting of the server with its option, its
                environment variable, and its default. These settings decide what a server starts with:
                """,
            )
            rows(
                listOf("Setting", "What it sets"),
                listOf(
                    listOf("`--definitions`", "The definitions location, from which the server loads its definitions."),
                    listOf("`--writable`", "Writable mode. Without it, the server starts in read-only mode."),
                    listOf("`--initial-definitions`", INITIAL_DEFINITIONS),
                    listOf("`--tag-filter`", "The tag filter of the server."),
                ),
            )
            prose("After the server has loaded its definitions, it applies the default plan, where there is one.")
        }

    private val ideas =
        HelpTopic(
            name = "ideas",
            title = "Things worth testing",
            summary = "A few ways in which a mocked service shows what an application does when things go wrong.",
            related = listOf("scenarios", "error-scenarios", "response-modes"),
        ) {
            prose(
                """
                A mock is most useful for what a real service does rarely. A few things are worth a test:

                - A slow service. Give a service a delay of two minutes, and see whether the application gives
                  up when its timeout says so. A real service sometimes accepts a connection and then does
                  not answer.
                - Garbage. Let a scenario answer with something that the application does not expect, such as
                  the word `GARBAGE` or half a JSON document, and see whether the application copes.
                - Failures. Give a scenario the status 500, 404, or 303, and see what the application makes of
                  it.
                - A service that is down. Put a service in proxy mode with a real URL at which nothing
                  answers, and see what the application does with the answer of the server.
                """,
            )
        }

    /** The topics, in the order of their list. */
    val all: List<HelpTopic> =
        listOf(
            services,
            scenarios,
            responseModes,
            matchCriteria,
            requestRules,
            errorScenarios,
            plans,
            twisting,
            tags,
            storage,
            import,
            recording,
            clients,
            merging,
            realUrls,
            schema,
            automation,
            ideas,
        )

    /** Returns the topic with the name [name]. */
    fun named(name: String): HelpTopic = all.first { it.name == name }

    private const val KEY_OF_A_RULE =
        "The name of the parameter or the header. Letter case does not matter, and `*` stands for all of them. " +
            "The groups `body` and `url` need no key."

    private const val STRING_REQUIRED = "A value contains the text. Letter case does not matter. An empty text only asks for a value."

    private const val INITIAL_DEFINITIONS =
        "A file, a directory, or a URL of definitions that take the place of the stored ones when the server starts."

    private const val RULE_SET = """
        {
          "parameters": [
            {
              "key": "ticker",
              "desc": "The ticker must contain the letter g.",
              "value_rule_arg": "g",
              "value_rule_type": "string_required"
            },
            {
              "key": "date",
              "desc": "A date is optional, but it must look like 11/23/2026.",
              "value_rule_arg": "^\\d{2}/\\d{2}/\\d{4}$",
              "value_rule_type": "regex_optional"
            }
          ],
          "headers": [
            {
              "key": "page_id",
              "value_rule_arg": "",
              "value_rule_type": "string_required"
            }
          ],
          "body": [
            {"value_rule_arg": "username", "value_rule_type": "string_required"}
          ],
          "url": [
            {"value_rule_arg": "\\b123\\b", "value_rule_type": "regex_required"}
          ]
        }
    """
}
