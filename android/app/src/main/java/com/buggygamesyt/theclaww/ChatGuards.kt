package com.buggygamesyt.theclaww

import org.json.JSONObject

/** Keeps error replies out of what the model reads, and catches tool calls a model printed as text instead of answering. */
object ChatGuards {
    /** Small models often forget they can search: remind them (with today's date) on every tool chat. Same text as desktop. */
    fun toolHint(nudge: Boolean, github: Boolean = false): String {
        val today = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date())
        return "Today is $today. You can use tools: WebSearch and WebFetch for anything current or online (weather, news, prices, scores, hours, facts you're unsure of), bash for commands and files. Never say you lack internet or real-time access; search instead." +
            " For weather, WebFetch https://wttr.in/CITY?format=4 (spaces as +, add ?u for °F), which returns plain text." +
            (if (github) " GitHub: you're signed in as the user. In bash, \$GITHUB_TOKEN is set: read repos, files, issues and Actions with curl -s -H \"Authorization: Bearer \$GITHUB_TOKEN\" https://api.github.com/... (start with /user and /user/repos), and clone or push with https://x-access-token:\$GITHUB_TOKEN@github.com/OWNER/REPO.git. Never print the token. Ask before pushing, deleting or changing anything on GitHub." else "") +
            (if (nudge) " Your last answer said you couldn't look this up. You can: call WebSearch now, then answer from the results." else "") + "\n\n"
    }
    private val noWeb = Regex("(real[- ]time|up[- ]to[- ]date|current) (weather|data|information|info|news|prices?)|(don'?t|do not|can'?t|cannot|unable to) (have )?(access|browse|search|check|look up|retrieve)[^.]{0,40}(internet|web|online|real[- ]time|external|current)|as an ai[^.]{0,40}(no|not|don'?t)[^.]{0,30}(internet|real[- ]time)", RegexOption.IGNORE_CASE)
    /** The reply claims it can't reach the web or live data. */
    fun saysNoWeb(t: String) = noWeb.containsMatchIn(t)
    const val PLAIN_WORDS = "Answer the user's last message in plain words. Do not output JSON, function calls or tool calls."
    fun toolCallNote(model: String) = "$model answered with a tool request instead of words, so there's nothing to show. " +
        "Try asking again, or pick another model for this kind of task in Settings › Models for tasks."

    private val errStart = Regex("^(error:|ollama error:|couldn't reach ollama|claw stopped \\(code|api returned \\d{3}|invalid_request_error|does not support tools|that was more than the model can hold)", RegexOption.IGNORE_CASE)
    private val errAnywhere = Regex("\\bapi returned \\d{3} [A-Z]|invalid_request_error|does not support tools")

    fun isErrorText(t: String): Boolean { val s = t.trim(); return errStart.containsMatchIn(s) || errAnywhere.containsMatchIn(s.take(400)) }

    /** History without error replies, and without the message that caused each one. */
    fun clean(prior: List<Msg>): List<Msg> {
        val out = ArrayList<Msg>()
        for (i in prior.indices) {
            val m = prior[i]; val next = prior.getOrNull(i + 1)
            if (!m.user && (m.error || isErrorText(m.text))) continue
            if (m.user && next != null && !next.user && (next.error || isErrorText(next.text))) continue
            out.add(m)
        }
        return out
    }

    /** True when a reply is (almost) only a JSON tool call, e.g. {"name": "ToolSearch", "parameters": {...}}, that nothing ran. */
    fun isToolCallText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 4000) return false
        var rest = t.replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
        var found = false
        var i = rest.indexOf('{')
        while (i >= 0) {
            var depth = 0; var j = i; var str = false
            while (j < rest.length) {
                val ch = rest[j]
                if (str) { if (ch == '\\') j++ else if (ch == '"') str = false }
                else if (ch == '"') str = true
                else if (ch == '{') depth++
                else if (ch == '}') { depth--; if (depth == 0) break }
                j++
            }
            if (depth != 0) break
            val call = try { JSONObject(rest.substring(i, j + 1)).let { it.optJSONObject("function") ?: it } } catch (_: Exception) { null }
            if (call != null && call.opt("name") is String && (call.opt("parameters") is JSONObject || call.has("arguments"))) {
                rest = rest.substring(0, i) + rest.substring(j + 1); found = true; i = rest.indexOf('{'); continue
            }
            i = rest.indexOf('{', i + 1)
        }
        return found && rest.replace(Regex("[\\s\\[\\],]"), "").length < 300
    }
}
