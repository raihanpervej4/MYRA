package com.myra.assistant.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class PrimeContact(val name: String, val number: String)

object Prefs {
    const val KEY_API = "api_key"
    const val KEY_NAME = "user_name"
    const val KEY_PERSONALITY = "personality_mode"
    const val KEY_MODEL = "gemini_model"
    const val KEY_VOICE = "gemini_voice"
    const val KEY_PRIME_JSON = "prime_contacts_json"

    const val MODE_GF = "GF"
    const val MODE_PRO = "PRO"
    const val MODE_ASSISTANT = "ASSISTANT"

    val MODELS = listOf(
        "Native Audio (Human Voice)" to "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "Flash Live (Fast)" to "models/gemini-2.0-flash-live-001",
        "Pro Audio Dialog" to "models/gemini-2.5-flash-preview-native-audio-dialog"
    )

    val VOICES = listOf(
        "Aoede (Female)" to "Aoede",
        "Charon (Male)" to "Charon",
        "Kore (Female)" to "Kore",
        "Fenrir (Male)" to "Fenrir",
        "Puck (Male)" to "Puck",
        "Leda (Female)" to "Leda",
        "Orus (Male)" to "Orus",
        "Zephyr (Female)" to "Zephyr"
    )

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString(KEY_API, "")?.trim() ?: ""
    fun userName(c: Context): String = sp(c).getString(KEY_NAME, "")?.trim().orEmpty().ifBlank { "Sir" }
    fun rawUserName(c: Context): String = sp(c).getString(KEY_NAME, "") ?: ""
    fun personality(c: Context): String = sp(c).getString(KEY_PERSONALITY, MODE_GF) ?: MODE_GF
    fun model(c: Context): String = sp(c).getString(KEY_MODEL, MODELS[0].second) ?: MODELS[0].second
    fun voice(c: Context): String = sp(c).getString(KEY_VOICE, "Aoede") ?: "Aoede"

    fun saveBasics(c: Context, api: String, name: String, mode: String, model: String, voice: String) {
        sp(c).edit()
            .putString(KEY_API, api.trim())
            .putString(KEY_NAME, name.trim())
            .putString(KEY_PERSONALITY, mode)
            .putString(KEY_MODEL, model)
            .putString(KEY_VOICE, voice)
            .apply()
    }

    fun getPrimeContacts(c: Context): MutableList<PrimeContact> {
        val prefs = sp(c)
        val json = prefs.getString(KEY_PRIME_JSON, null)
        val list = mutableListOf<PrimeContact>()
        if (json == null) {
            // legacy migration
            val n = prefs.getString("prime_name", null)
            val num = prefs.getString("prime_number", null)
            if (!n.isNullOrBlank() && !num.isNullOrBlank()) {
                list.add(PrimeContact(n, num))
                savePrimeContacts(c, list)
            }
            return list
        }
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(PrimeContact(o.optString("name"), o.optString("number")))
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun savePrimeContacts(c: Context, list: List<PrimeContact>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("number", it.number)) }
        sp(c).edit().putString(KEY_PRIME_JSON, arr.toString()).apply()
    }
}
