package com.woodward.tailcodex.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.Color

class RichTextParserTest {
    @Test
    fun keepsCodeOpaqueAndRecognizesInlineAndDisplayMath() {
        val blocks = RichTextParser.parse(
            "before \$x\$ and \\(z+1\\)\n```kotlin\nval x = \"\$notMath\$\"\n```\n\$\$y^2\$\$\n\\[w^3\\]",
        )
        assertTrue(blocks.any { it is RichTextBlock.InlineMath && it.latex == "x" })
        assertTrue(blocks.any { it is RichTextBlock.Code && it.language == "kotlin" && "\$notMath\$" in it.source })
        assertTrue(blocks.any { it is RichTextBlock.DisplayMath && it.latex == "y^2" })
        assertTrue(blocks.any { it is RichTextBlock.InlineMath && it.latex == "z+1" })
        assertTrue(blocks.any { it is RichTextBlock.DisplayMath && it.latex == "w^3" })
        assertEquals("a+b", RaTeXMathRenderer.normalize(" a+b "))
    }

    @Test
    fun syntaxHighlighterPreservesSourceAndAddsTokenStyles() {
        val source = "val answer = 42 // result"
        val highlighted = SyntaxHighlighter.highlight(
            source,
            "kotlin",
            SyntaxPalette(Color.Blue, Color.Red, Color.Magenta, Color.Gray, Color.Black),
        )
        assertEquals(source, highlighted.text)
        assertTrue(highlighted.spanStyles.size >= 3)
    }
}
