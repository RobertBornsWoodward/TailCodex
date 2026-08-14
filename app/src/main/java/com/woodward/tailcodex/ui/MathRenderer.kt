package com.woodward.tailcodex.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import io.ratex.RaTeXView

interface MathRenderer {
    fun normalize(latex: String): String = latex.trim()

    @Composable
    fun Render(latex: String, display: Boolean, fontSize: Float, modifier: Modifier = Modifier)
}

/** The concrete engine is isolated here so the Markdown and conversation UI can swap it later. */
object RaTeXMathRenderer : MathRenderer {
    @Composable
    override fun Render(latex: String, display: Boolean, fontSize: Float, modifier: Modifier) {
        val normalized = normalize(latex)
        if (LocalInspectionMode.current) {
            Text(normalized, modifier = modifier, color = MaterialTheme.colorScheme.onSurface)
            return
        }
        val color = MaterialTheme.colorScheme.onSurface
        AndroidView(
            modifier = modifier,
            factory = { context -> RaTeXView(context) },
            update = { view ->
                view.latex = normalized
                view.fontSize = fontSize
                view.displayMode = display
                view.color = color.toArgb()
            },
        )
    }

    private fun Color.toArgb(): Int = android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
}
