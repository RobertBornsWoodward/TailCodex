package com.woodward.tailcodex.security

import android.content.Context
import androidx.core.content.edit

class ThreadPinStore(context: Context) {
    private val preferences = context.getSharedPreferences("tailcodex_pins", Context.MODE_PRIVATE)
    fun isPinned(hostId: String, threadId: String): Boolean = preferences.getBoolean(key(hostId, threadId), false)
    fun setPinned(hostId: String, threadId: String, pinned: Boolean) {
        preferences.edit {
            if (pinned) putBoolean(key(hostId, threadId), true) else remove(key(hostId, threadId))
        }
    }
    private fun key(hostId: String, threadId: String) = "$hostId:$threadId"
}
