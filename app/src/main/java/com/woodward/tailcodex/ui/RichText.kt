package com.woodward.tailcodex.ui

sealed interface RichTextBlock {
    data class Markdown(val source: String) : RichTextBlock
    data class Code(val language: String?, val source: String) : RichTextBlock
    data class InlineMath(val latex: String) : RichTextBlock
    data class DisplayMath(val latex: String) : RichTextBlock
}

/** Keeps fenced code opaque, then normalizes supported Markdown math delimiters into typed blocks. */
object RichTextParser {
    private enum class Token { FENCE, DISPLAY_DOLLAR, DISPLAY_BRACKET, INLINE_DOLLAR, INLINE_PAREN }
    private data class Candidate(val index: Int, val token: Token)

    fun parse(source: String): List<RichTextBlock> {
        if (source.isEmpty()) return emptyList()
        val blocks = mutableListOf<RichTextBlock>()
        var cursor = 0
        while (cursor < source.length) {
            val candidate = nextCandidate(source, cursor)
            if (candidate == null) {
                blocks.addMarkdown(source.substring(cursor))
                break
            }
            if (candidate.index > cursor) blocks.addMarkdown(source.substring(cursor, candidate.index))
            when (candidate.token) {
                Token.FENCE -> cursor = parseFence(source, candidate.index, blocks)
                Token.DISPLAY_DOLLAR -> cursor = parseDelimited(
                    source, candidate.index, "$$", "$$", display = true, target = blocks,
                )
                Token.DISPLAY_BRACKET -> cursor = parseDelimited(
                    source, candidate.index, "\\[", "\\]", display = true, target = blocks,
                )
                Token.INLINE_DOLLAR -> cursor = parseDelimited(
                    source, candidate.index, "$", "$", display = false, target = blocks,
                )
                Token.INLINE_PAREN -> cursor = parseDelimited(
                    source, candidate.index, "\\(", "\\)", display = false, target = blocks,
                )
            }
        }
        return blocks
    }

    private fun nextCandidate(source: String, from: Int): Candidate? = buildList {
        source.indexOf("```", from).takeIf { it >= 0 }?.let { add(Candidate(it, Token.FENCE)) }
        source.indexOf("$$", from).takeIf { it >= 0 }?.let { add(Candidate(it, Token.DISPLAY_DOLLAR)) }
        source.indexOf("\\[", from).takeIf { it >= 0 }?.let { add(Candidate(it, Token.DISPLAY_BRACKET)) }
        findInlineDollar(source, from).takeIf { it >= 0 }?.let { add(Candidate(it, Token.INLINE_DOLLAR)) }
        source.indexOf("\\(", from).takeIf { it >= 0 }?.let { add(Candidate(it, Token.INLINE_PAREN)) }
    }.minWithOrNull(compareBy<Candidate> { it.index }.thenBy { it.token.ordinal })

    private fun parseFence(source: String, start: Int, target: MutableList<RichTextBlock>): Int {
        val headerEnd = source.indexOf('\n', start + 3)
        if (headerEnd < 0) {
            target.addMarkdown(source.substring(start))
            return source.length
        }
        val end = source.indexOf("```", headerEnd + 1)
        if (end < 0) {
            target.addMarkdown(source.substring(start))
            return source.length
        }
        val language = source.substring(start + 3, headerEnd).trim().ifBlank { null }
        target += RichTextBlock.Code(language, source.substring(headerEnd + 1, end))
        return end + 3
    }

    private fun parseDelimited(
        source: String,
        start: Int,
        opening: String,
        closing: String,
        display: Boolean,
        target: MutableList<RichTextBlock>,
    ): Int {
        val contentStart = start + opening.length
        val end = if (closing == "$") findClosingDollar(source, contentStart) else source.indexOf(closing, contentStart)
        if (end < 0) {
            target.addMarkdown(source.substring(start))
            return source.length
        }
        val latex = source.substring(contentStart, end).trim()
        if (latex.isEmpty()) {
            target.addMarkdown(source.substring(start, end + closing.length))
        } else if (display) {
            target += RichTextBlock.DisplayMath(latex)
        } else {
            target += RichTextBlock.InlineMath(latex)
        }
        return end + closing.length
    }

    private fun findInlineDollar(source: String, from: Int): Int {
        var index = source.indexOf('$', from)
        while (index >= 0) {
            val escaped = index > 0 && source[index - 1] == '\\'
            val doubled = index + 1 < source.length && source[index + 1] == '$'
            if (!escaped && !doubled) return index
            index = source.indexOf('$', index + if (doubled) 2 else 1)
        }
        return -1
    }

    private fun findClosingDollar(source: String, from: Int): Int {
        var index = source.indexOf('$', from)
        while (index >= 0) {
            if (source[index - 1] != '\\' && (index + 1 >= source.length || source[index + 1] != '$')) return index
            index = source.indexOf('$', index + 1)
        }
        return -1
    }

    private fun MutableList<RichTextBlock>.addMarkdown(value: String) {
        if (value.isEmpty()) return
        val previous = lastOrNull() as? RichTextBlock.Markdown
        if (previous == null) add(RichTextBlock.Markdown(value))
        else this[lastIndex] = previous.copy(source = previous.source + value)
    }
}
