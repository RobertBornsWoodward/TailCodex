package com.woodward.tailcodex

import android.os.Bundle
import android.content.Intent
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.woodward.tailcodex.presentation.TailCodexViewModel
import com.woodward.tailcodex.ui.TailCodexApp
import com.woodward.tailcodex.ui.TailCodexTheme

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private lateinit var tailCodexViewModel: TailCodexViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tailCodexViewModel = ViewModelProvider(this)[TailCodexViewModel::class.java]
        tailCodexViewModel.handleNotificationIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        enableEdgeToEdge()
        setContent {
            TailCodexTheme {
                TailCodexApp(tailCodexViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tailCodexViewModel.handleNotificationIntent(intent)
    }
}
