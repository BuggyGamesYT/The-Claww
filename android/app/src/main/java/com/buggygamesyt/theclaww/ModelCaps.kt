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
    private val sizes = HashMap<String, Long>()
    private var at = 0L

    @Synchronized
    fun refreshDigests(host: String, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - at < 15000) return
        try {
            val a = JSONObject(URL("$host/api/tags").readText()).getJSONArray("models")
            for (i in 0 until a.length()) { val m = a.getJSONObject(i); digests[Advisor.norm(m.getString("name"))] = m.optString("digest"); sizes[Advisor.norm(m.getString("name"))] = m.optLong("size") }
            at = System.currentTimeMillis()
        } catch (_: Exception) {}
    }

    /** Cached tool support: true, false, or null when not known yet (or the model changed). */
    fun known(prefs: SharedPreferences, name: String): Boolean? {
        val k = Advisor.norm(name)
        val s = prefs.getString("caps_$k", null) ?: return null
        val j = try { JSONObject(s) } catch (_: Exception) { return null }
        val d = synchronized(this) { digests[k] }
        if (j.optInt("v") != 2 || (d != null && j.optString("digest") != d)) return null
        return j.optBoolean("tools", true)
    }

    /** KV-cache elements per token (2 x layers x KV heads x head size), the model's max context and its size on disk. */
    class Shape(val kvElems: Long, val maxCtx: Long, val size: Long)
    fun shape(prefs: SharedPreferences, name: String): Shape? {
        if (known(prefs, name) == null) return null
        val k = Advisor.norm(name)
        val j = try { JSONObject(prefs.getString("caps_$k", null) ?: return null) } catch (_: Exception) { return null }
        val size = synchronized(this) { sizes[k] } ?: 0L
        if (j.optLong("kv") <= 0 || size <= 0) return null
        return Shape(j.optLong("kv"), j.optLong("maxCtx"), size)
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
            save(prefs, name, tools, j.optJSONObject("model_info")); tools
        } catch (_: Exception) { null }
    }

    fun markNoTools(prefs: SharedPreferences, name: String) {
        val k = Advisor.norm(name)
        val old = try { JSONObject(prefs.getString("caps_$k", null) ?: "{}") } catch (_: Exception) { JSONObject() }
        val d = synchronized(this) { digests[k] } ?: old.optString("digest")
        prefs.edit().putString("caps_$k", old.put("v", 2).put("digest", d).put("tools", false).toString()).apply()
    }

    private fun save(prefs: SharedPreferences, name: String, tools: Boolean, mi: JSONObject?) {
        val k = Advisor.norm(name)
        val d = synchronized(this) { digests[k] } ?: ""
        val o = JSONObject().put("v", 2).put("digest", d).put("tools", tools)
        if (mi != null) {
            // From model_info: "<arch>.block_count", ".attention.head_count(_kv)", ".embedding_length", ".attention.key/value_length".
            val arch = mi.optString("general.architecture")
            fun num(key: String): Long {
                val v = mi.opt("$arch.$key") ?: return 0L
                return if (v is org.json.JSONArray) (0 until v.length()).maxOfOrNull { v.optLong(it) } ?: 0L else (v as? Number)?.toLong() ?: v.toString().toLongOrNull() ?: 0L
            }
            val layers = num("block_count"); val heads = num("attention.head_count"); val kv = num("attention.head_count_kv").takeIf { it > 0 } ?: heads
            val emb = num("embedding_length")
            val kl = num("attention.key_length").takeIf { it > 0 } ?: (if (heads > 0) emb / heads else 0L)
            val vl = num("attention.value_length").takeIf { it > 0 } ?: kl
            o.put("kv", layers * kv * (kl + vl)).put("maxCtx", num("context_length"))
        }
        prefs.edit().putString("caps_$k", o.toString()).apply()
    }
}

/** Memory budget: "Use up to N GB" becomes the biggest context that fits next to the model's weights. */
object Budget {
    private val STEPS = longArrayOf(4096, 6144, 8192, 12288, 16384, 24576, 32768, 49152, 65536, 98304, 131072, 196608, 262144)
    class Plan(val ctx: Int, val bytes: Long, val tooBig: Boolean)

    /** [kvBytes]: bytes per KV element, 1 for a q8_0 cache (the built-in Ollama), 2 for f16. Null until the model's shape is known.
     *  Too big is only a warning: the model still runs with the minimum sensible context (4K) and Ollama loads what it can. */
    fun plan(prefs: SharedPreferences, name: String, gb: Int, kvBytes: Int): Plan? {
        if (gb <= 0 || name.isBlank()) return null
        val sh = ModelCaps.shape(prefs, name) ?: return null
        val perTok = sh.kvElems * kvBytes
        val overhead = 800_000_000L + sh.size / 20
        val avail = gb * 1_000_000_000L - sh.size - overhead
        var ctx = avail / perTok
        if (sh.maxCtx > 0) ctx = minOf(ctx, sh.maxCtx)
        if (ctx < 4096) return Plan(4096, sh.size + overhead + 4096 * perTok, true)
        val step = STEPS.filter { it <= ctx }.maxOrNull() ?: 4096L
        return Plan(step.toInt(), sh.size + overhead + step * perTok, false)
    }
}
