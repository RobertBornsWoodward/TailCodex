package com.woodward.tailcodex.security

import android.content.Context
import androidx.core.content.edit

class DraftStore(context: Context) {
    private val preferences = context.getSharedPreferences("tailcodex_drafts", Context.MODE_PRIVATE)

    fun load(hostId: String, threadId: String): String =
        preferences.getString(key(hostId, threadId), "").orEmpty()

    fun save(hostId: String, threadId: String, value: String) {
        preferences.edit {
            if (value.isBlank()) remove(key(hostId, threadId)) else putString(key(hostId, threadId), value)
        }
    }

    private fun key(hostId: String, threadId: String) = "${hostId.hashCode()}:${threadId.hashCode()}"
}
