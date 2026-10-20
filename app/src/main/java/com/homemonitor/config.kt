package com.homemonitor

import android.content.Context
import org.json.JSONObject
import java.io.InputStreamReader

object Config {
    private var jsonConfig: JSONObject? = null

    fun init(context: Context) {
        if (jsonConfig != null) return
        try {
            val inputStream = context.assets.open("app_config.json")
            val reader = InputStreamReader(inputStream)
            val jsonString = reader.readText()
            reader.close()
            jsonConfig = JSONObject(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            jsonConfig = JSONObject()
        }
    }

    val BOT_TOKEN: String
        get() = jsonConfig?.optString("bot_token", "") ?: ""

    val ALLOWED_CHAT_ID: String
        get() = jsonConfig?.optString("chat_id", "") ?: ""

    fun getString(key: String, defaultVal: String = ""): String {
        return jsonConfig?.optString(key, defaultVal) ?: defaultVal
    }

    fun getBoolean(key: String, defaultVal: Boolean = false): Boolean {
        return jsonConfig?.optBoolean(key, defaultVal) ?: defaultVal
    }

    fun getInt(key: String, defaultVal: Int = 0): Int {
        return jsonConfig?.optInt(key, defaultVal) ?: defaultVal
    }
}
