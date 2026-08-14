// GENERATED from protocol/codex-0.147.0 schemas. Do not add domain or UI behavior here.
package com.woodward.tailcodex.protocol.generated

import org.json.JSONArray
import org.json.JSONObject

data class WireThreadReadParams(val threadId: String, val includeTurns: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("threadId", threadId).put("includeTurns", includeTurns)
}

data class WireCommandApprovalParams(
    val threadId: String,
    val turnId: String,
    val itemId: String,
    val command: String?,
    val cwd: String?,
    val reason: String?,
    val networkHost: String?,
    val networkProtocol: String?,
    val availableDecisions: Set<String>,
) {
    companion object {
        fun fromJson(value: JSONObject): WireCommandApprovalParams {
            val network = value.optJSONObject("networkApprovalContext")
            val decisions = value.optJSONArray("availableDecisions")
            return WireCommandApprovalParams(
                threadId = value.getString("threadId"),
                turnId = value.getString("turnId"),
                itemId = value.getString("itemId"),
                command = value.nullableString("command"),
                cwd = value.nullableString("cwd"),
                reason = value.nullableString("reason"),
                networkHost = network?.nullableString("host"),
                networkProtocol = network?.nullableString("protocol"),
                availableDecisions = if (decisions == null) emptySet() else buildSet {
                    for (index in 0 until decisions.length()) decisions.optString(index).takeIf(String::isNotBlank)?.let(::add)
                },
            )
        }
    }
}

data class WireFileApprovalParams(
    val threadId: String,
    val turnId: String,
    val itemId: String,
    val reason: String?,
    val grantRoot: String?,
) {
    companion object {
        fun fromJson(value: JSONObject) = WireFileApprovalParams(
            value.getString("threadId"),
            value.getString("turnId"),
            value.getString("itemId"),
            value.nullableString("reason"),
            value.nullableString("grantRoot"),
        )
    }
}

data class WirePermissionsApprovalParams(
    val threadId: String,
    val turnId: String,
    val itemId: String,
    val cwd: String,
    val reason: String?,
    val permissions: JSONObject,
) {
    companion object {
        fun fromJson(value: JSONObject) = WirePermissionsApprovalParams(
            value.getString("threadId"),
            value.getString("turnId"),
            value.getString("itemId"),
            value.getString("cwd"),
            value.nullableString("reason"),
            value.optJSONObject("permissions") ?: JSONObject(),
        )
    }
}

data class WireUserInputOption(val label: String, val description: String)

data class WireUserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<WireUserInputOption>,
    val isOther: Boolean,
    val isSecret: Boolean,
)

data class WireToolRequestUserInputParams(
    val threadId: String,
    val turnId: String,
    val itemId: String,
    val questions: List<WireUserInputQuestion>,
    val isBlocking: Boolean,
) {
    companion object {
        fun fromJson(value: JSONObject): WireToolRequestUserInputParams {
            val wireQuestions = value.optJSONArray("questions") ?: JSONArray()
            val questions = buildList {
                for (index in 0 until wireQuestions.length()) {
                    val question = wireQuestions.optJSONObject(index) ?: continue
                    val wireOptions = question.optJSONArray("options") ?: JSONArray()
                    val options = buildList {
                        for (optionIndex in 0 until wireOptions.length()) {
                            val option = wireOptions.optJSONObject(optionIndex) ?: continue
                            add(WireUserInputOption(option.optString("label"), option.optString("description")))
                        }
                    }
                    add(
                        WireUserInputQuestion(
                            question.getString("id"),
                            question.getString("header"),
                            question.getString("question"),
                            options,
                            question.optBoolean("isOther"),
                            question.optBoolean("isSecret"),
                        ),
                    )
                }
            }
            return WireToolRequestUserInputParams(
                value.getString("threadId"),
                value.getString("turnId"),
                value.getString("itemId"),
                questions,
                value.getBoolean("isBlocking"),
            )
        }
    }
}

data class WireMcpElicitationParams(
    val threadId: String,
    val turnId: String?,
    val serverName: String,
    val mode: String,
    val message: String,
    val requestedSchema: JSONObject?,
    val url: String?,
) {
    companion object {
        fun fromJson(value: JSONObject) = WireMcpElicitationParams(
            threadId = value.getString("threadId"),
            turnId = value.optString("turnId").takeIf(String::isNotBlank),
            serverName = value.getString("serverName"),
            mode = value.getString("mode"),
            message = value.getString("message"),
            requestedSchema = value.optJSONObject("requestedSchema"),
            url = value.optString("url").takeIf(String::isNotBlank),
        )
    }
}

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
