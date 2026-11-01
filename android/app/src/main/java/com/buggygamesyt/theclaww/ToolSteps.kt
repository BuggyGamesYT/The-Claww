package com.buggygamesyt.theclaww

import org.json.JSONArray
import org.json.JSONObject

/** Turns claw's JSON output into a clean reply, and its CLAW_TOOL events into a live status line. */
object ToolSteps {
    /** Invisible marker: the bubble text is a live status line, not the reply. */
    const val LIVE = "\u2063"

    fun reply(raw: String): String {
        val j = raw.lines().asReversed().firstNotNullOfOrNull { l ->
            val t = l.trim()
            if (!t.startsWith("{")) null else try { JSONObject(t).takeIf { it.has("message") } } catch (_: Exception) { null }
        } ?: return raw.trim()
        return j.optString("message").trim()
    }

    /** "CLAW_TOOL {json}" line from claw's stderr -> status like "Searching the web for …", else null. */
    fun status(line: String): String? {
        val i = line.indexOf("CLAW_TOOL ")
        if (i < 0) return null
        val o = try { JSONObject(line.substring(i + 10).trim()) } catch (_: Exception) { return null }
        return live(o)
    }

    private fun live(u: JSONObject): String {
        val input = parse(u.opt("input"))
        fun f(vararg k: String) = k.firstNotNullOfOrNull { input.optString(it).takeIf { v -> v.isNotBlank() } } ?: ""
        fun file(p: String) = p.substringAfterLast('/').ifBlank { "a file" }
        return when (u.optString("name")) {
            "WebSearch", "web_search" -> "Searching the web for ${short(f("query"), 50)}"
            "WebFetch", "web_fetch" -> "Reading ${short(f("url").removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/'), 40)}"
            "bash", "Bash" -> "Using the terminal"
            "read_file", "Read" -> "Reading ${short(file(f("file_path", "filePath", "path")), 40)}"
            "write_file", "Write" -> "Writing ${short(file(f("file_path", "filePath", "path")), 40)}"
            "edit_file", "Edit" -> "Editing ${short(file(f("file_path", "filePath", "path")), 40)}"
            "glob_search", "Glob" -> "Looking for files"
            "grep_search", "Grep" -> "Searching your code"
            "TodoWrite" -> "Planning the steps"
            "Agent", "Task" -> "Handing off a sub-task"
            else -> "Using ${u.optString("name")}"
        }
    }

    private fun parse(raw: Any?): JSONObject = when (raw) {
        is JSONObject -> raw
        is String -> try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        else -> JSONObject()
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
