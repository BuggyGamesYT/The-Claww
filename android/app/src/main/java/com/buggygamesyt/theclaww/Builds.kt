package com.buggygamesyt.theclaww

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Build watcher: a project can watch a GitHub repo's Actions builds. When a build fails, the project's chat gets the
 * errors and is asked to fix, commit, push and start the build again. When one passes, it can be asked to test it.
 * At most MAX automatic turns in a row; anything the user types in the project resets the count. Same as desktop.
 */
object Builds {
    const val MAX = 3
    class Event(val project: String, val prompt: String)

    fun repo(p: SharedPreferences, pid: String) = p.getString("ciRepo_$pid", "")!!.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
    fun branch(p: SharedPreferences, pid: String) = p.getString("ciBranch_$pid", "")!!.trim()
    fun fix(p: SharedPreferences, pid: String) = p.getBoolean("ciFix_$pid", true)
    fun test(p: SharedPreferences, pid: String) = p.getBoolean("ciTest_$pid", false)
    fun on(p: SharedPreferences, pid: String) = repo(p, pid).contains('/')
    fun userSpoke(p: SharedPreferences, pid: String) { p.edit().putInt("ciTries_$pid", 0).apply() }
    fun status(p: SharedPreferences, pid: String) = p.getString("ciStatus_$pid", "") ?: ""

    private fun get(token: String, path: String): String {
        val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "TheClaww-builds")
        c.instanceFollowRedirects = false
        val code = c.responseCode
        if (code in 300..399) { // job logs: a short-lived link to the log file, fetched without the token
            val loc = c.getHeaderField("Location") ?: throw Exception("no log link"); c.disconnect()
            return (URL(loc).openConnection() as HttpURLConnection).run { connectTimeout = 15000; readTimeout = 60000; inputStream.bufferedReader().use { it.readText() } }
        }
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw Exception("GitHub said HTTP $code")
        return text
    }

    /** The lines of a build log that explain the failure, trimmed to fit a small model's memory. */
    fun errorLines(log: String, max: Int = 3500): String {
        val bad = Regex("(?i)(\\berror\\b|^e: |failed|failure|exception|cannot find|unresolved|not found|undefined|expected|panicked|syntaxerror|traceback)")
        val noise = Regex("(?i)(process completed with exit code|##\\[group]|##\\[endgroup]|deprecat|warning:|--stacktrace|--info or --debug|--scan|help\\.gradle|run with)")
        val lines = log.lines().map { it.replace(Regex("^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z\\s?"), "").replace("##[error]", "").trimEnd() }
            .filter { it.isNotBlank() && bad.containsMatchIn(it) && !noise.containsMatchIn(it) }
            .distinct().takeLast(30)
        var out = lines.joinToString("\n")
        if (out.length > max) out = out.takeLast(max)
        return out.ifBlank { log.lines().filter { it.isNotBlank() }.takeLast(25).joinToString("\n").takeLast(max) }
    }

    /** Checks each watching project once. Returns the turns to post. */
    fun check(p: SharedPreferences, token: String, projects: List<Project>): List<Event> {
        val out = mutableListOf<Event>()
        for (pr in projects) {
            val pid = pr.id
            if (!on(p, pid)) continue
            val repo = repo(p, pid); val br = branch(p, pid)
            try {
                val runs = JSONObject(get(token, "/repos/$repo/actions/runs?per_page=10" + (if (br.isNotEmpty()) "&branch=$br" else ""))).getJSONArray("workflow_runs")
                if (runs.length() == 0) continue
                val last = p.getLong("ciLast_$pid", 0)
                // Newest finished run we haven't seen. The first check only remembers where things are.
                var run: JSONObject? = null
                for (i in 0 until runs.length()) { val r = runs.getJSONObject(i); if (r.optString("status") == "completed") { run = r; break } }
                val running = (0 until runs.length()).any { runs.getJSONObject(it).optString("status") != "completed" }
                if (run == null) continue
                val id = run.getLong("id")
                val name = run.optString("name"); val num = run.optInt("run_number"); val sha = run.optString("head_sha").take(7)
                val branchName = run.optString("head_branch"); val url = run.optString("html_url")
                val concl = run.optString("conclusion")
                p.edit().putString("ciStatus_$pid", "$name #$num: ${if (running) "newer build running" else concl.ifEmpty { "done" }}").apply()
                if (last == 0L) { p.edit().putLong("ciLast_$pid", id).apply(); continue }
                if (id <= last) continue
                p.edit().putLong("ciLast_$pid", id).apply()
                if (concl == "cancelled" || concl == "skipped") continue
                val tries = p.getInt("ciTries_$pid", 0)
                if (concl == "success") {
                    if (!test(p, pid)) { p.edit().putInt("ciTries_$pid", 0).apply(); continue }
                    if (tries >= MAX) continue
                    p.edit().putInt("ciTries_$pid", tries + 1).apply()
                    out += Event(pid, "[Build watcher] GitHub build \"$name\" #$num passed on $branchName ($sha): $url\n" +
                        "Test it: in this project folder, clone https://github.com/$repo (or git pull if it's already here) on $branchName, " +
                        "then run its tests and checks (unit tests, a compile or syntax check, a linter, whatever this repo has). " +
                        "If you find a real bug, fix it, commit, and push. If everything passes, don't push anything; just tell me the result.")
                    continue
                }
                if (!fix(p, pid)) continue
                if (tries >= MAX) {
                    if (tries == MAX) { p.edit().putInt("ciTries_$pid", MAX + 1).apply()
                        out += Event(pid, "[Build watcher] Build \"$name\" #$num failed again on $branchName ($sha) after $MAX automatic fixes: $url\n" +
                            "Don't change anything yet. Explain in plain words what's still failing and what you'd try next, and wait for me.") }
                    continue
                }
                p.edit().putInt("ciTries_$pid", tries + 1).apply()
                val jobs = JSONObject(get(token, "/repos/$repo/actions/runs/$id/jobs?per_page=50")).getJSONArray("jobs")
                val failed = (0 until jobs.length()).map { jobs.getJSONObject(it) }.filter { it.optString("conclusion") == "failure" }.take(2)
                val errs = failed.joinToString("\n\n") { j ->
                    val log = try { get(token, "/repos/$repo/actions/jobs/${j.getLong("id")}/logs") } catch (e: Exception) { "(couldn't read the log: ${e.message})" }
                    "Job \"${j.optString("name")}\":\n" + errorLines(log, if (failed.size > 1) 1800 else 3500)
                }
                val wf = run.optString("path").substringAfterLast('/')
                out += Event(pid, "[Build watcher] GitHub build \"$name\" #$num failed on $branchName ($sha): $url\n" +
                    "Errors from the log:\n```\n$errs\n```\n" +
                    "Fix it: in this project folder, clone https://github.com/$repo (or git pull if it's already here) on $branchName, find the cause in the code, " +
                    "fix it, check it compiles or passes a syntax check if you can, then commit and push to $branchName. " +
                    (if (wf.isNotEmpty()) "If the build doesn't start on its own after the push, start it with a POST to /repos/$repo/actions/workflows/$wf/dispatches with {\"ref\":\"$branchName\"}. " else "") +
                    "Then tell me in a sentence or two what was wrong and what you changed. (Automatic fix ${tries + 1} of $MAX.)")
            } catch (e: Exception) {
                p.edit().putString("ciStatus_$pid", "Couldn't check: ${e.message}").apply()
            }
        }
        return out
    }
}
