package com.buggygamesyt.theclaww

import org.json.JSONArray
import org.json.JSONObject

/** Turns claw's JSON output into a clean reply: one short line per tool used, then the answer. */
object ToolSteps {
    fun reply(raw: String): String {
        val j = raw.lines().asReversed().firstNotNullOfOrNull { l ->
            val t = l.trim()
            if (!t.startsWith("{")) null else try { JSONObject(t).takeIf { it.has("message") } } catch (_: Exception) { null }
        } ?: return raw.trim()
        val steps = lines(j.optJSONArray("tool_uses"))
        val msg = j.optString("message").trim()
        return (if (steps.isEmpty()) "" else steps.joinToString("\n") + "\n\n") + msg
    }

    fun lines(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { line(it) } }.distinct()
    }

    private fun short(s: String, n: Int = 70) = s.replace(Regex("\\s+"), " ").trim().let { if (it.length > n) it.take(n - 1) + "…" else it }

    private fun line(u: JSONObject): String {
        val name = u.optString("name")
        val raw = u.opt("input")
        val input = when (raw) {
            is JSONObject -> raw
            is String -> try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            else -> JSONObject()
        }
        fun f(vararg k: String) = k.firstNotNullOfOrNull { input.optString(it).takeIf { v -> v.isNotBlank() } } ?: ""
        return when (name) {
            "WebSearch", "web_search" -> "🔎 Searched the web: ${short(f("query"))}"
            "WebFetch", "web_fetch" -> "🌐 Read ${short(f("url"))}"
            "bash", "Bash" -> "⌨️ Ran ${short(f("command"), 60)}"
            "read_file", "Read" -> "📄 Read ${short(f("file_path", "filePath", "path"))}"
            "write_file", "Write" -> "✏️ Wrote ${short(f("file_path", "filePath", "path"))}"
            "edit_file", "Edit" -> "📝 Edited ${short(f("file_path", "filePath", "path"))}"
            "glob_search", "Glob", "grep_search", "Grep" -> "🔎 Searched files: ${short(f("pattern"))}"
            "TodoWrite" -> "🗒️ Updated the to-do list"
            else -> "🔧 Used $name"
        }
    }
}
