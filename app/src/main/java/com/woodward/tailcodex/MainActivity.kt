package com.woodward.tailcodex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.woodward.tailcodex.data.TailCodexViewModel
import com.woodward.tailcodex.ui.TailCodexApp
import com.woodward.tailcodex.ui.TailCodexTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TailCodexTheme {
                val viewModel: TailCodexViewModel = viewModel()
                TailCodexApp(viewModel)
            }
        }
    }
}
