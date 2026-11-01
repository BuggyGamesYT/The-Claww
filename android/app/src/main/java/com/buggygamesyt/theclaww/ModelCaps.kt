package com.buggygamesyt.theclaww

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What each model can do, from Ollama's /api/show "capabilities", cached per model + digest.
 * claw always sends tool definitions, so a model without "tools" (e.g. gemma3) gets a direct Ollama chat instead.
 * Network calls block: use them off the main thread.
 */
object ModelCaps {
    private val digests = HashMap<String, String>()
    private var at = 0L

    @Synchronized
    fun refreshDigests(host: String, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - at < 15000) return
        try {
            val a = JSONObject(URL("$host/api/tags").readText()).getJSONArray("models")
            for (i in 0 until a.length()) { val m = a.getJSONObject(i); digests[Advisor.norm(m.getString("name"))] = m.optString("digest") }
            at = System.currentTimeMillis()
        } catch (_: Exception) {}
    }

    /** Cached tool support: true, false, or null when not known yet (or the model changed). */
    fun known(prefs: SharedPreferences, name: String): Boolean? {
        val k = Advisor.norm(name)
        val s = prefs.getString("caps_$k", null) ?: return null
        val j = try { JSONObject(s) } catch (_: Exception) { return null }
        val d = synchronized(this) { digests[k] }
        if (d != null && j.optString("digest") != d) return null
        return j.optBoolean("tools", true)
    }

    fun noTools(prefs: SharedPreferences, name: String) = known(prefs, name) == false

    /** Tool support for [name], asking Ollama when it isn't cached. Null if Ollama can't be reached. */
    fun tools(prefs: SharedPreferences, host: String, name: String): Boolean? {
        if (name.isBlank()) return null
        refreshDigests(host)
        known(prefs, name)?.let { return it }
        return try {
            val c = URL("$host/api/show").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.connectTimeout = 4000; c.readTimeout = 8000
            c.outputStream.use { it.write(JSONObject().put("model", name).toString().toByteArray()) }
            if (c.responseCode >= 400) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val caps = j.optJSONArray("capabilities")
            // Older Ollama has no capabilities list: assume tools work (the error fallback still catches it).
            val tools = caps == null || (0 until caps.length()).any { caps.optString(it) == "tools" }
            save(prefs, name, tools); tools
        } catch (_: Exception) { null }
    }

    fun markNoTools(prefs: SharedPreferences, name: String) = save(prefs, name, false)

    private fun save(prefs: SharedPreferences, name: String, tools: Boolean) {
        val k = Advisor.norm(name)
        val d = synchronized(this) { digests[k] } ?: ""
        prefs.edit().putString("caps_$k", JSONObject().put("digest", d).put("tools", tools).toString()).apply()
    }
}
