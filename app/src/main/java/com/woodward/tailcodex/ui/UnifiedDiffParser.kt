package com.woodward.tailcodex.ui

data class FileDiff(
    val path: String,
    val unifiedDiff: String,
    val additions: Int,
    val deletions: Int,
)

object UnifiedDiffParser {
    fun parse(source: String): List<FileDiff> {
        if (source.isBlank()) return emptyList()
        val sections = mutableListOf<MutableList<String>>()
        source.lines().forEach { line ->
            if (line.startsWith("diff --git ") && sections.lastOrNull()?.isNotEmpty() == true) {
                sections.add(mutableListOf())
            }
            if (sections.isEmpty()) sections.add(mutableListOf())
            sections.last() += line
        }
        return sections.filter { it.isNotEmpty() }.mapIndexed { index, lines ->
            val path = lines.firstNotNullOfOrNull { line ->
                when {
                    line.startsWith("+++ b/") -> line.removePrefix("+++ b/")
                    line.startsWith("+++ ") && line != "+++ /dev/null" -> line.removePrefix("+++ ")
                    line.startsWith("--- a/") -> line.removePrefix("--- a/")
                    else -> null
                }
            } ?: "file-${index + 1}"
            FileDiff(
                path = path,
                unifiedDiff = lines.joinToString("\n"),
                additions = lines.count { it.startsWith("+") && !it.startsWith("+++") },
                deletions = lines.count { it.startsWith("-") && !it.startsWith("---") },
            )
        }
    }
}
