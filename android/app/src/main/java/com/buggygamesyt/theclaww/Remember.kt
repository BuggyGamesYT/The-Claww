package com.buggygamesyt.theclaww

import android.content.SharedPreferences
import org.json.JSONArray

/** Remember (optional): facts the user asks every chat to know. Same behavior and wording as the desktop app. */
object Remember {
    fun on(p: SharedPreferences) = p.getBoolean("rememberOn", false)
    fun list(p: SharedPreferences): MutableList<String> {
        val a = try { JSONArray(p.getString("remember", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getString(it) }.toMutableList()
    }
    private fun save(p: SharedPreferences, l: List<String>) = p.edit().putString("remember", JSONArray(l).toString()).apply()
    fun add(p: SharedPreferences, t: String) { val l = list(p); if (l.none { it.equals(t, true) }) { l.add(t); save(p, l) } }
    fun remove(p: SharedPreferences, i: Int) { val l = list(p); if (i in l.indices) { l.removeAt(i); save(p, l) } }
    fun forget(p: SharedPreferences, q: String): Int { val l = list(p); val k = l.filterNot { it.contains(q, true) }; save(p, k); return l.size - k.size }
    fun text(p: SharedPreferences): String {
        val l = list(p)
        return if (on(p) && l.isNotEmpty()) "Things the user asked you to remember (use them when they matter, don't recite them):\n" + l.joinToString("\n") { "- $it" } + "\n\n" else ""
    }
    private val rememberRe = Regex("(?is)^(?:please\\s+)?(?:can you\\s+)?remember(?:\\s+that)?\\s*[:,\\-]?\\s+(.+)$")
    private val forgetRe = Regex("(?is)^(?:please\\s+)?forget(?:\\s+that)?(?:\\s+about)?\\s+(.+)$")
    private val whatRe = Regex("(?i)^(what do you remember|what have you remembered|what do you know about me)\\b")
    /** "remember that …" / "forget …" / "what do you remember" typed in a chat, when Remember is on. Returns the reply, or null. */
    fun command(p: SharedPreferences, text: String): String? {
        if (!on(p)) return null
        rememberRe.find(text)?.let { m ->
            val t = m.groupValues[1].trim().trimEnd('.', '!')
            if (t.isNotEmpty()) { add(p, t); return "Got it. I'll remember that ${t.replaceFirst(Regex("(?i)^i "), "you ").replaceFirst(Regex("(?i)^my "), "your ")}." }
        }
        forgetRe.find(text)?.let { m ->
            val q = m.groupValues[1].trim().trimEnd('.', '!')
            val n = forget(p, q)
            return if (n > 0) "Done, I forgot ${if (n == 1) "that" else "$n things"}." else "I don't have anything saved about \"$q\"."
        }
        if (whatRe.containsMatchIn(text)) {
            val l = list(p)
            return if (l.isNotEmpty()) "Here's what I remember:\n" + l.joinToString("\n") { "• $it" } else "Nothing yet. Say \"remember that …\" and I'll keep it for every chat."
        }
        return null
    }
}
