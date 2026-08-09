package com.cennoxx.widgetrelay.tasker.widgets

/**
 * What the text/regex form of "Click Widget" matches against an element's
 * text or content description
 * against: either an exact (trimmed) string, or - written as `/pattern/flags`,
 * the familiar JS/grep convention - a regular expression, so `/^coffee$/i`
 * matches "COFFEE" and "Coffee" alike.
 *
 * A query only becomes a pattern if it *looks* like one; anything else,
 * including plain text that happens to start with a slash, stays literal. That
 * is a real ambiguity, not a technicality - a button labelled "/2" is
 * indistinguishable from a two-character pattern with no flags - but it is the
 * same trade-off every tool using this convention makes, and slash-wrapped
 * widget text is rare enough that it is the right side to err on.
 */
sealed class TextQuery {
    abstract fun matches(candidate: String): Boolean

    class Literal(private val text: String) : TextQuery() {
        override fun matches(candidate: String) = candidate.trim() == text
    }

    /** Search, not full-match - `/coffee/i` matches "Buy coffee" too. */
    class Pattern(private val regex: Regex) : TextQuery() {
        override fun matches(candidate: String) = regex.containsMatchIn(candidate.trim())
    }

    companion object {
        // Greedy .* backtracks to the rightmost "/flags" at the end of the
        // string, so a pattern that itself contains unescaped slashes still
        // parses correctly.
        private val SYNTAX = Regex("^/(.*)/([a-zA-Z]*)$", RegexOption.DOT_MATCHES_ALL)
        private val KNOWN_FLAGS = mapOf(
            'i' to RegexOption.IGNORE_CASE,
            'm' to RegexOption.MULTILINE,
            's' to RegexOption.DOT_MATCHES_ALL
        )

        /**
         * Parses [query] (already trimmed by the caller). Returns null if it
         * looks like a `/pattern/flags` query but the pattern or a flag is
         * invalid - the caller reports that as a configuration error rather
         * than silently falling back to a literal match nobody typed.
         */
        fun parse(query: String): TextQuery? {
            val match = SYNTAX.matchEntire(query) ?: return Literal(query)
            val (pattern, flags) = match.destructured
            val options = flags.map { KNOWN_FLAGS[it.lowercaseChar()] ?: return null }.toSet()
            return try {
                Pattern(Regex(pattern, options))
            } catch (e: java.util.regex.PatternSyntaxException) {
                null
            }
        }
    }
}
