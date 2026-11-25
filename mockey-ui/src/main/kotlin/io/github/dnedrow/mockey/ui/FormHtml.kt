package io.github.dnedrow.mockey.ui

import kotlinx.html.ButtonType
import kotlinx.html.CommonAttributeGroupFacade
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormEncType
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.fileInput
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.role
import kotlinx.html.select
import kotlinx.html.textArea
import kotlinx.html.textInput

/*
 * The forms of the pages. A form that changes something is sent with the method POST and holds the token of
 * the page, see [FormTokens]. A field shows its value, a hint, and what the server has against the value, so
 * that a form that was not taken is shown as it was sent, with every message next to the field that it
 * concerns.
 */

/**
 * A form of the page of [view] that changes something. A browser sends it to [action], which is a path of
 * the UI, and with the files that were chosen in it when it is [withFiles]. The form has the classes
 * [classes], which say how it looks.
 */
internal fun FlowContent.postForm(
    view: View,
    action: String,
    withFiles: Boolean = false,
    classes: String? = null,
    block: FORM.() -> Unit,
) {
    val encType = if (withFiles) FormEncType.multipartFormData else null
    form(action = action, method = FormMethod.post, encType = encType, classes = classes) {
        hiddenInput(name = FormTokens.FIELD) { value = view.formToken }
        block()
    }
}

/**
 * A form that asks for a page: a browser sends it to [action], which is a path of the UI, with the method
 * GET. So what its fields hold is in the address of the page that it leads to, which shows the same
 * wherever it is opened. The form changes nothing and holds no token.
 */
internal fun FlowContent.getForm(
    action: String,
    classes: String? = null,
    block: FORM.() -> Unit,
) {
    form(action = action, method = FormMethod.get, classes = classes, block = block)
}

/**
 * Says of the fields [names] of the form what the page gives them, which is what [state] holds, see [Shown].
 * The UI then asks the server to set only what a person changed in those fields.
 */
internal fun FORM.shownOf(
    state: FormState,
    names: List<String>,
) {
    for (name in names) hiddenInput(name = Shown.PREFIX + name) { value = state.shownMark(name) }
}

/** Says at the head of a form that the form was not taken, when [state] is that of such a form. */
internal fun FlowContent.rejectionOf(state: FormState) {
    if (!state.isRejected) return
    p(classes = "problem") {
        role = "alert"
        // What the server says of the form as a whole says little where it has messages about the fields.
        +(state.detail?.takeUnless { state.hasMessages } ?: "Nothing was changed: the form holds what the server does not take. See below.")
    }
}

/** Shows where a form ends what the server has against it that no field of the form showed. */
internal fun FlowContent.otherProblemsOf(state: FormState) {
    val others = state.others()
    if (others.isEmpty()) return
    div(classes = "field-problem") { others.forEach { p { +it } } }
}

/**
 * A field for a line of text. The field has the name [name] in the form and shows the value that [state]
 * has for it, under [label] and with [hint]. It shows what the server has against [api], which is the member
 * of a request to the admin API that the field is for.
 */
internal fun FlowContent.textField(
    state: FormState,
    name: String,
    label: String,
    api: String = name,
    hint: String? = null,
) {
    field(state, name, label, api, hint) { describe ->
        textInput(name = name) {
            id = idOf(name)
            value = state.value(name)
            describe()
        }
    }
}

/** A field for lines of text, see [textField]. It is [rows] lines high. */
internal fun FlowContent.textAreaField(
    state: FormState,
    name: String,
    label: String,
    api: String = name,
    hint: String? = null,
    rows: Int = DEFAULT_ROWS,
) {
    field(state, name, label, api, hint) { describe ->
        textArea(rows = "$rows") {
            id = idOf(name)
            attributes["name"] = name
            describe()
            // A browser drops a line feed that follows the start of the field, so one is written for it to drop.
            +"\n${state.value(name)}"
        }
    }
}

/**
 * A field in which one of [choices] is picked, each of which is the value that the form sends and what the
 * page calls it, see [textField].
 */
internal fun FlowContent.choiceField(
    state: FormState,
    name: String,
    label: String,
    choices: List<Pair<String, String>>,
    api: String = name,
    hint: String? = null,
) {
    field(state, name, label, api, hint) { describe ->
        select {
            id = idOf(name)
            attributes["name"] = name
            describe()
            for ((value, called) in choices) {
                option {
                    this.value = value
                    selected = state.value(name) == value
                    +called
                }
            }
        }
    }
}

/**
 * A field in which a file is chosen, see [textField]. The form that it is in is one that is sent with its
 * files, see [postForm]. A browser shows no file in such a field that a person did not choose there, so the
 * field is empty when the form is shown again.
 */
internal fun FlowContent.fileField(
    state: FormState,
    name: String,
    label: String,
    api: String = name,
    hint: String? = null,
) {
    field(state, name, label, api, hint) { describe ->
        fileInput(name = name) {
            id = idOf(name)
            describe()
        }
    }
}

/** A box that is checked or not, see [textField]. A form sends the field only when the box is checked. */
internal fun FlowContent.checkField(
    state: FormState,
    name: String,
    label: String,
    api: String = name,
    hint: String? = null,
) {
    val messages = state.messagesAt(api)
    div(classes = "field check") {
        checkBoxInput(name = name) {
            id = idOf(name)
            value = CHECKED
            checked = state.isChecked(name)
            describedBy(name, hint, messages)
        }
        label {
            htmlFor = idOf(name)
            +label
        }
        notesOf(name, hint, messages)
    }
}

/**
 * A field that is made of several controls, such as the rows of a list, which [controls] writes. The group
 * is called [label], and it shows [hint] and what the server has against [apis], which are the members of a
 * request to the admin API that the group is for. [name] is the name of its controls in the form.
 */
internal fun FlowContent.fieldGroup(
    state: FormState,
    name: String,
    label: String,
    apis: List<String>,
    hint: String? = null,
    controls: FlowContent.() -> Unit,
) {
    val messages = apis.flatMap(state::messagesAt)
    fieldSet(classes = "field") {
        val ofHint = "${idOf(name)}-hint".takeIf { hint != null }
        val describing = listOfNotNull(ofHint, "${idOf(name)}-problem".takeIf { messages.isNotEmpty() })
        if (describing.isNotEmpty()) attributes["aria-describedby"] = describing.joinToString(" ")
        legend { +label }
        controls()
        notesOf(name, hint, messages)
    }
}

/**
 * The button that sends the form that it is in. It says [label]. A button that deletes something says so by
 * its look, when it is [dangerous].
 */
internal fun FlowContent.submit(
    label: String,
    dangerous: Boolean = false,
) {
    button(type = ButtonType.submit, classes = if (dangerous) "danger" else null) { +label }
}

/** The value that a check box sends when it is checked. */
internal const val CHECKED = "on"

private const val DEFAULT_ROWS = 8

private fun idOf(name: String): String = "f-$name"

private fun FlowContent.field(
    state: FormState,
    name: String,
    label: String,
    api: String,
    hint: String?,
    control: FlowContent.(describe: CommonAttributeGroupFacade.() -> Unit) -> Unit,
) {
    val messages = state.messagesAt(api)
    div(classes = "field") {
        label {
            htmlFor = idOf(name)
            +label
        }
        control { describedBy(name, hint, messages) }
        notesOf(name, hint, messages)
    }
}

/** Says of a control which elements describe it, and that its value is not valid when there are [messages]. */
private fun CommonAttributeGroupFacade.describedBy(
    name: String,
    hint: String?,
    messages: List<String>,
) {
    val describing = listOfNotNull("${idOf(name)}-hint".takeIf { hint != null }, "${idOf(name)}-problem".takeIf { messages.isNotEmpty() })
    if (describing.isNotEmpty()) attributes["aria-describedby"] = describing.joinToString(" ")
    if (messages.isNotEmpty()) attributes["aria-invalid"] = "true"
}

private fun FlowContent.notesOf(
    name: String,
    hint: String?,
    messages: List<String>,
) {
    if (hint != null) {
        p(classes = "hint") {
            id = "${idOf(name)}-hint"
            +hint
        }
    }
    if (messages.isNotEmpty()) {
        div(classes = "field-problem") {
            id = "${idOf(name)}-problem"
            messages.forEach { p { +it } }
        }
    }
}

/**
 * The step in which the UI asks whether something is to be deleted or reset. A link or a button of a page
 * leads to a page that says what is about to happen, see [page]. Nothing happens before the form of that
 * page is sent, which holds [FIELD]. The step needs no scripting.
 */
internal object Confirmation {
    /** The field with which the form of the page says that the one who sends it was asked. */
    const val FIELD = "confirmed"

    private const val YES = "yes"

    /** Returns whether [input] is the form of a page that asked. */
    fun isGiven(input: FormInput): Boolean = input.text(FIELD) == YES

    /**
     * Returns the page that asks. Its title is the question, such as whether a service is to be deleted, and
     * [consequences] say what happens then. The button of its form says [confirm], and the form is sent to
     * [action]. The link beside it leads to [back] and changes nothing.
     */
    fun page(
        view: View,
        title: String,
        consequences: List<String>,
        confirm: String,
        action: String,
        back: String,
    ): Page =
        Page(title) {
            consequences.forEach { p { +it } }
            postForm(view, action) {
                hiddenInput(name = FIELD) { value = YES }
                p(classes = "actions") {
                    submit(confirm, dangerous = true)
                    +" "
                    a(href = back) { +"Cancel" }
                }
            }
        }
}
