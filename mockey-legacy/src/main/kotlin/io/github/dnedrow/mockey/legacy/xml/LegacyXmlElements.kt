package io.github.dnedrow.mockey.legacy.xml

import org.w3c.dom.Element

/** The elements that are children of this element, in their order. */
internal fun Element.childElements(): List<Element> = generateSequence(firstChild) { it.nextSibling }.filterIsInstance<Element>().toList()

/** The children of this element that are named [name], in their order. A namespace prefix is no part of the name of an element. */
internal fun Element.childElements(name: String): List<Element> = childElements().filter { (it.localName ?: it.nodeName) == name }

/** The first child of this element that is named [name], or `null` when it has none. */
internal fun Element.childElement(name: String): Element? = childElements(name).firstOrNull()
