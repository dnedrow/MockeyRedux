package io.github.dnedrow.mockey.ui

/*
 * The walk through the whole UI, which opens every page and submits every form of it. The coverage test
 * takes it with a browser without scripting (see [UiCoverageTest]), and the test of the script takes it a
 * second time with a browser that runs the script of the UI (see [UiEnhancementTest]).
 */

/**
 * Walks through the whole UI. The stand-in server of the walk has to hold the samples (see [holdSamples]).
 * Each step has a name, which the coverage list of [UiCoverageTest] refers to.
 */
internal fun Journey.walkTheWholeUi() {
    val overview = step("the overview") { open("/") }
    overviewOf(overview)
    servicesFrom(overview)
    scenariosFrom(overview)
    mergeAndDerivationFrom(overview)
    plansFrom(overview)
    twistsFrom(overview)
    tagsFrom(overview)
    historyFrom(overview)
    definitionsFrom(overview)
    serverFrom(overview)
    helpFrom(overview)
}

private fun Journey.overviewOf(overview: Browser.Response) {
    step("the tag filter of the overview") { submit(overview.formTo("/services"), mapOf("tag" to "team-a")) }
    step("the search") { submit(overview.formTo("/search"), mapOf("term" to "found")) }
    step("the response mode switch") {
        val switch = overview.formTo("/services/mode")
        submit(switch, pressed = switch.select("button[aria-pressed=false]").first())
    }
    step("the default scenario switch") {
        submit(overview.formTo("/services/default-scenario"), mapOf("scenario" to "sc-missing"))
    }
    step("the diagnostics") { follow(overview, "a[href=/diagnostics]") }
    step("the files of a page") {
        follow(overview, "link[rel=stylesheet]")
        follow(overview, "link[rel=icon]")
        follow(overview, "script[src]")
    }
}

private fun Journey.servicesFrom(overview: Browser.Response) {
    val created =
        step("the form for a new service") {
            val form = follow(overview, "a[href=/service/new]").formTo("/service/new")
            submit(form, mapOf("name" to "Billing", "mockUrl" to "billing"))
        }
    val edited =
        step("the form that edits a service") {
            val form = follow(created, "a[href^=/service/edit]").formTo("/service/edit")
            submit(form, mapOf("schema" to """{"type":"object"}""", "schemaEnabled" to "on"))
        }
    step("the schema report") { follow(edited, "a[href^=/service/schema-report]") }
    val copy = step("the button that duplicates a service") { submit(edited.formTo("/service/duplicate")) }
    step("the confirmation that deletes a service") {
        submit(follow(copy, "a[href^=/service/delete]").formTo("/service/delete"))
    }
}

private fun Journey.scenariosFrom(overview: Browser.Response) {
    val service = step("the page of a service") { follow(overview, "a[href='/service?id=svc-customers']") }
    step("the page of a scenario") { follow(service, "main table.scenarios a[href^='/scenario?']") }
    val created =
        step("the form for a new scenario") {
            val form = follow(service, "a[href^=/scenario/new]").formTo("/scenario/new")
            submit(form, mapOf("name" to "extra", "body" to "more"))
        }
    val edited = step("the form that edits a scenario") { submit(created.formTo("/scenario"), mapOf("status" to "201")) }
    val asDefault = step("the button that makes a scenario the default scenario") { submit(edited.formTo("/scenario/default")) }
    val asError = step("the button that makes a scenario the error scenario") { submit(asDefault.formTo("/scenario/error")) }
    val noError = step("the button that ends the designation as the error scenario") { submit(asError.formTo("/scenario/error")) }
    val universal =
        step("the button that makes a scenario the universal error scenario") {
            submit(noError.formTo("/scenario/universal-error"))
        }
    val ended =
        step("the button that ends the designation as the universal error scenario") {
            submit(universal.formTo("/scenario/universal-error"))
        }
    step("the confirmation that deletes a scenario") {
        submit(follow(ended, "a[href^=/scenario/delete]").formTo("/scenario/delete"))
    }
}

private fun Journey.mergeAndDerivationFrom(overview: Browser.Response) {
    step("the merge of services") {
        val service = follow(overview, "a[href='/service?id=svc-customers']")
        val choice = follow(service, "a[href^=/service/merge]").formTo("/service/merge")
        val billing = choice.select("input[type=checkbox]").first { it.parent()!!.text().startsWith("Billing") }
        val asked = submit(choice, rows = mapOf("source" to listOf(billing.attr("value"))))
        submit(asked.formTo("/service/merge"))
    }
    step("the derivation of real URLs") {
        val form = follow(overview, "a[href=/services/real-urls]").formTo("/services/real-urls")
        submit(form, mapOf("expression" to "qa1", "replacements" to "qa2"))
    }
}

private fun Journey.plansFrom(overview: Browser.Response) {
    val list = step("the list of plans") { follow(overview, "nav a[href=/plans]") }
    step("the page of a plan") { follow(list, "a[href='/plan?id=plan-night']") }
    val created =
        step("the form for a new plan") {
            submit(follow(list, "a[href=/plan/new]").formTo("/plan/new"), mapOf("name" to "Day"))
        }
    val edited =
        step("the form that edits a plan") {
            submit(follow(created, "a[href^=/plan/edit]").formTo("/plan/edit"), mapOf("description" to "For the day shift"))
        }
    val saved = step("the form that saves settings into a plan") { submit(follow(edited, "a[href^=/plan/save]").formTo("/plan/save")) }
    step("the button that applies a plan") { submit(saved.formTo("/plan/apply")) }
    val asDefault = step("the button that makes a plan the default plan") { submit(saved.formTo("/plan/default")) }
    val noDefault = step("the button that ends the designation as the default plan") { submit(asDefault.formTo("/plan/default")) }
    step("the confirmation that deletes a plan") {
        submit(follow(noDefault, "a[href^=/plan/delete]").formTo("/plan/delete"))
    }
}

private fun Journey.twistsFrom(overview: Browser.Response) {
    val list = step("the list of twist configurations") { follow(overview, "nav a[href=/twists]") }
    step("the page of a twist configuration") { follow(list, "a[href='/twist?id=twist-german']") }
    val created =
        step("the form for a new twist configuration") {
            val form = follow(list, "a[href=/twist/new]").formTo("/twist/new")
            val pair = mapOf("origination" to listOf("client"), "destination" to listOf("customer"))
            submit(form, mapOf("name" to "French paths"), rows = pair)
        }
    val edited =
        step("the form that edits a twist configuration") { submit(created.formTo("/twist"), mapOf("name" to "Paths in French")) }
    val enabled = step("the button that enables a twist configuration") { submit(edited.formTo("/twist/enabled")) }
    val disabled = step("the button that disables a twist configuration") { submit(enabled.formTo("/twist/enabled")) }
    step("the confirmation that deletes a twist configuration") {
        submit(follow(disabled, "a[href^=/twist/delete]").formTo("/twist/delete"))
    }
}

private fun Journey.tagsFrom(overview: Browser.Response) {
    val tags = step("the page for tags") { follow(overview, "nav a[href=/tags]") }
    val filtered = step("the form that sets the tag filter") { submit(tags.formTo("/tags/filter"), mapOf("tags" to "happy")) }
    val off = step("the button that turns the tag filter off") { submit(filtered.formTo("/tags/filter-off")) }
    step("the confirmation that removes a tag") {
        submit(follow(off, "a[href^=/tag/remove]").formTo("/tag/remove"))
    }
}

private fun Journey.historyFrom(overview: Browser.Response) {
    val list = step("the history") { follow(overview, "nav a[href=/history]") }
    step("the filter of the history") { submit(list.formTo("/history"), mapOf("tokens" to "customers")) }
    val flagged = step("the button that flags a history entry") { submit(list.formTo("/history/flag")) }
    val entry = step("the page of a history entry") { follow(flagged, "a[href^=/history/entry]") }
    step("the button that flags a history entry") { submit(entry.formTo("/history/flag")) }
    val replayed =
        step("the form that replays a history entry") {
            submit(follow(entry, "a[href^=/history/replay]").formTo("/history/replay"))
        }
    step("the form that saves a history entry as a scenario") {
        submit(follow(entry, "a[href^=/history/scenario]").formTo("/history/scenario"), mapOf("name" to "recorded"))
    }
    step("the button that deletes a history entry") { submit(replayed.formTo("/history/remove")) }
    step("the confirmation that deletes history entries") {
        submit(follow(list, "a[href=/history/delete]").formTo("/history/delete"))
    }
    val statistics = step("the statistics") { follow(list, "a[href=/statistics]") }
    step("the time range of the statistics") { submit(statistics.formTo("/statistics"), mapOf("from" to "2026-01-01 00:00:00")) }
    step("the downloads of the statistics") {
        follow(statistics, "a[href^=/statistics/download][href*=csv]")
        follow(statistics, "a[href^=/statistics/download][href*=json]")
    }
}

private fun Journey.definitionsFrom(overview: Browser.Response) {
    val definitions = step("the page for import and export") { follow(overview, "nav a[href=/definitions]") }
    step("the download of the definitions") { follow(definitions, "a[href=/definitions/export]") }
    step("the form that imports a document") {
        val document = Browser.Chosen("definitions.json", """{"formatVersion":1}""".toByteArray())
        submit(definitions.formTo("/definitions/import"), mapOf("tags" to "imported"), mapOf("document" to document))
    }
}

private fun Journey.helpFrom(overview: Browser.Response) {
    val topics = step("the help") { follow(overview, "nav a[href=/help]") }
    step("the topics of the help") {
        for (link in topics.page.select("main dl.topics a")) follow(topics, "main dl.topics a[href=${link.attr("href")}]")
    }
}

private fun Journey.serverFrom(overview: Browser.Response) {
    val server = step("the page of the server") { follow(overview, "nav a[href=/system]") }
    val proxy = step("the page for the outbound proxy") { follow(server, "a[href=/proxy]") }
    step("the form that sets the outbound proxy") { submit(proxy.formTo("/proxy"), mapOf("url" to "http://proxy.example.com:8080")) }
    step("the button that clears the upstream session") { submit(proxy.formTo("/proxy/clear-session")) }
    val writable =
        step("the confirmation that switches to writable mode") {
            submit(follow(server, "a[href=/system/writable]").formTo("/system/writable"))
        }
    step("the button that switches to read-only mode") { submit(writable.formTo("/system/read-only")) }
    step("the confirmation that reloads the definitions") {
        submit(follow(server, "a[href=/system/reload]").formTo("/system/reload"))
    }
    step("the button that clears the last-used times") { submit(server.formTo("/system/clear-last-used")) }
    step("the confirmation that resets the server") {
        submit(follow(server, "a[href=/system/reset]").formTo("/system/reset"))
    }
}
