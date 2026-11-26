package io.github.dnedrow.mockey.ui

import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.p

/**
 * A role that one item out of several can hold, such as the default scenario of a service or the default
 * plan. A page describes the role in a sentence and offers a single button that either assigns or removes it.
 *
 * [held] is the sentence for an item that holds the role, and [notHeld] is the one for an item that doesn't.
 * [assign] and [remove] are the button labels. A role without a [remove] label can't be removed. It only
 * moves on when another item takes it over.
 */
internal class Designation(
    val held: String,
    val notHeld: String,
    val assign: String,
    val remove: String?,
) {
    internal companion object {
        /** The field that a designation button sends: [YES] to assign the role, or [NO] to remove it. */
        const val FIELD = "designate"
        const val YES = "yes"
        const val NO = "no"

        /**
         * Returns whether a designation button asked to assign its role.
         *
         * @throws InvalidInput if the form doesn't say whether to assign the role or to remove it.
         */
        fun isAssigned(input: FormInput): Boolean =
            when (input.text(FIELD)) {
                YES -> true
                NO -> false
                else -> throw InvalidInput(FIELD, "The form does not say whether to assign the designation or to remove it.")
            }

        /**
         * Returns the notice to show after a designation action. [subject] names the item, for example "The
         * plan Night", and [role] names the role, for example "the default plan". [assigned] says whether the
         * action asked to assign the role or to remove it, and [wasHeld] says whether the item held the role
         * before.
         */
        fun notice(
            subject: String,
            role: String,
            assigned: Boolean,
            wasHeld: Boolean,
        ): String =
            when {
                assigned -> "$subject is now $role."
                wasHeld -> "$subject is no longer $role."
                else -> "$subject was not $role. Nothing was changed."
            }
    }
}

/**
 * Shows whether the item of the page holds [designation], along with the button that changes it. [isHeld]
 * says whether the item holds the role right now. The button submits its form to [action].
 */
internal fun FlowContent.designationOf(
    view: View,
    designation: Designation,
    isHeld: Boolean,
    action: String,
) {
    div(classes = "designation") {
        p { +(if (isHeld) designation.held else designation.notHeld) }
        val label = if (isHeld) designation.remove else designation.assign
        if (label != null) {
            postForm(view, action, classes = "inline") {
                button(type = ButtonType.submit, name = Designation.FIELD) {
                    value = if (isHeld) Designation.NO else Designation.YES
                    +label
                }
            }
        }
    }
}
