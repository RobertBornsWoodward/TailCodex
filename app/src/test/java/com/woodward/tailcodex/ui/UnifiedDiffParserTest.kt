package com.woodward.tailcodex.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedDiffParserTest {
    @Test
    fun splitsFilesAndCountsUnifiedDiffLines() {
        val files = UnifiedDiffParser.parse(
            """diff --git a/A.kt b/A.kt
--- a/A.kt
+++ b/A.kt
@@ -1 +1 @@
-old
+new
diff --git a/B.kt b/B.kt
--- a/B.kt
+++ b/B.kt
@@ -0,0 +1 @@
+added""",
        )
        assertEquals(listOf("A.kt", "B.kt"), files.map { it.path })
        assertEquals(1, files[0].additions)
        assertEquals(1, files[0].deletions)
        assertEquals(1, files[1].additions)
    }
}
