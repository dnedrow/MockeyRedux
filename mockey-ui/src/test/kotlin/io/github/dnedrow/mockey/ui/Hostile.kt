package io.github.dnedrow.mockey.ui

/**
 * Content that is written to be read as more than the text that it is, for the tests that show that the pages
 * of the UI read it as text, wherever a server states it.
 */
internal object Hostile {
    /** Content that a browser would read as more than text if a page wrote it as it is. */
    val MARKUP =
        listOf(
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "\"><svg onload=alert(1)>",
            "' onmouseover='alert(1)",
            "\" onfocus=\"alert(1)\" autofocus=\"",
            "</pre><script>alert(1)</script>",
            "</textarea><script>alert(1)</script>",
            "</title><script>alert(1)</script>",
            "</option></select><script>alert(1)</script>",
            "--><script>alert(1)</script><!--",
            "&lt;b&gt; &amp;amp; &#x3C;i&#x3E; &quot;",
            "<b>bold</b> & <i>italic</i>",
            "]]><b>",
            "{{7*7}} \${7*7} <%= 7*7 %>",
        )

    /** Content that would lead a link elsewhere if an address held it as it is. */
    val ADDRESSES =
        listOf(
            "javascript:alert(1)",
            "//evil.example/x",
            "http://evil.example/x",
            "../../system/reset",
            "..",
            "a/b?c=d#e",
            "\\\\evil.example\\x",
            "data:text/html,<script>alert(1)</script>",
            " leading space",
            "line\nbreak",
        )

    /** What a document does not hold when every `<` and every `>` of the content is written as text. */
    val UNWRITTEN = listOf("<script", "<img", "<svg", "<b>", "<i>", "<!--", "-->", "]]>")

    /** How an address of a page of the UI is written, whatever it holds: a path, and parameters with their escapes. */
    val SAFE_ADDRESS = Regex("/[A-Za-z0-9._~%/-]*(\\?[A-Za-z0-9._~%=&-]*)?")
}
