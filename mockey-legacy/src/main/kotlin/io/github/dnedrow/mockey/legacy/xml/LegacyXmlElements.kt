package io.github.dnedrow.mockey.legacy.xml

import org.w3c.dom.Element

/** The elements that are children of this element, in their order. */
internal fun Element.childElements(): List<Element> = generateSequence(firstChild) { it.nextSibling }.filterIsInstance<Element>().toList()

/** The children of this element that are named [name], in their order. */
internal fun Element.childElements(name: String): List<Element> = childElements().filter { it.isNamed(name) }

/** The first child of this element that is named [name], or `null` when it has none. */
internal fun Element.childElement(name: String): Element? = childElements(name).firstOrNull()

/** Tells whether the element is named [name]. A namespace prefix is no part of the name of an element. */
internal fun Element.isNamed(name: String): Boolean = (localName ?: nodeName) == name

/** The value of the attribute [name], or `null` when the element has no such attribute. */
internal fun Element.attribute(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

/**
 * The text of the element as the loader of Mockey 2.x takes it: without the whitespace around it. Whitespace
 * is what `String.trim` of Java removes, which is every character up to the space.
 */
internal fun Element.text(): String = textContent.trim { it <= ' ' }
