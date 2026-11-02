package com.buggygamesyt.theclaww

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date

/**
 * Sync (optional): chats, projects and the Remember list through a private GitHub repo "claww-sync".
 * Same format as the desktop app: index.json {v, chats:{id:{title,project,updated}}, projects:[…], deleted:{id:ms}, remember:{list,updated}}
 * and chats/<id>.json {id,title,project,updated,msgs:[{u,t,a:[{name,mime}],s,e}]}. Newest copy of each chat wins. Files stay on their device.
 */
object Sync {
    const val REPO = "claww-sync"
    @Volatile var status = ""
    @Volatile private var running = false
    @Volatile private var again = false
    var onStatus: ((String) -> Unit)? = null
    /** Called on the main thread with the ids of chats that changed here, after a sync brought something in. */
    var onChanged: ((Set<String>) -> Unit)? = null
    var isBusy: () -> Boolean = { false }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val later = Runnable { lastCtx?.let { run(it) } }
    private var lastCtx: Context? = null

    fun on(p: SharedPreferences) = p.getBoolean("syncOn", false)

    fun soon(ctx: Context) {
        lastCtx = ctx.applicationContext
        if (!on(prefs(ctx))) return
        handler.removeCallbacks(later); handler.postDelayed(later, 4000)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("claww", Context.MODE_PRIVATE)

    private class HttpErr(val code: Int, msg: String) : Exception(msg)

    private fun api(token: String, method: String, path: String, body: JSONObject? = null): String {
        val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 20000; c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "TheClaww-sync")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw HttpErr(code, "HTTP $code: ${text.take(200)}")
        return text
    }

    private fun b64e(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
    private fun b64d(s: String) = String(Base64.decode(s.replace("\n", ""), Base64.DEFAULT))

    private class F(val sha: String?, val data: JSONObject?)
    private fun file(t: String, o: String, path: String): F = try {
        val j = JSONObject(api(t, "GET", "/repos/$o/$REPO/contents/$path")); F(j.getString("sha"), JSONObject(b64d(j.getString("content"))))
    } catch (e: HttpErr) { if (e.code == 404) F(null, null) else throw e }

    private fun put(t: String, o: String, path: String, data: JSONObject, sha: String?) {
        val b = JSONObject().put("message", "sync $path").put("content", b64e(data.toString()))
        if (sha != null) b.put("sha", sha)
        try { api(t, "PUT", "/repos/$o/$REPO/contents/$path", b) }
        catch (e: HttpErr) {
            // The file is already there (an earlier sync stopped halfway, or another device wrote it): use its current version.
            if (e.code != 422 && e.code != 409) throw e
            if (path == "index.json") throw e
            val cur = file(t, o, path).sha ?: throw e
            api(t, "PUT", "/repos/$o/$REPO/contents/$path", b.put("sha", cur))
        }
    }

    private fun owner(t: String, p: SharedPreferences): String {
        var o = p.getString("syncOwner", "") ?: ""
        if (o.isEmpty()) { o = JSONObject(api(t, "GET", "/user")).getString("login"); p.edit().putString("syncOwner", o).apply() }
        try { api(t, "GET", "/repos/$o/$REPO") } catch (e: HttpErr) {
            if (e.code != 404) throw e
            api(t, "POST", "/user/repos", JSONObject().put("name", REPO).put("private", true).put("auto_init", true).put("description", "The Claww chat sync (private)"))
            Thread.sleep(1500)
        }
        return o
    }

    private fun set(s: String) { status = s; handler.post { onStatus?.invoke(s) } }

    /** Runs one sync in the background. [manual] runs it even when the switch is off. */
    fun run(ctx: Context, manual: Boolean = false) {
        lastCtx = ctx.applicationContext
        val p = prefs(ctx)
        if (!on(p) && !manual) return
        if (running) { again = true; return }
        if (isBusy()) { soon(ctx); return }
        running = true; set("Syncing…")
        Thread {
            try {
                val token = Secrets.get(ctx, GitHubMcp.TOKEN)
                if (token.isNullOrBlank()) set("Add a GitHub token first.")
                else {
                    val changed = mutableSetOf<String>()
                    val n = once(token, p, changed, true)
                    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                    set("Synced $time" + if (n > 0) " · $n updated here" else "")
                    if (n > 0) handler.post { onChanged?.invoke(changed) }
                }
            } catch (e: HttpErr) {
                set(when (e.code) { 401 -> "GitHub didn't accept the token."; 403, 404 -> "The token needs the repo scope."; else -> "Couldn't sync: ${e.message?.take(120)}" })
            } catch (e: Exception) { set("Couldn't sync: ${e.message?.take(120)}") }
            running = false
            if (again) { again = false; handler.post { soon(ctx) } }
        }.start()
    }

    private fun once(t: String, p: SharedPreferences, changed: MutableSet<String>, retry: Boolean): Int {
        val o = owner(t, p)
        val idx = file(t, o, "index.json")
        val r = idx.data ?: JSONObject()
        val rChats = r.optJSONObject("chats") ?: JSONObject()
        val rProjects = r.optJSONArray("projects") ?: JSONArray()
        val rDel = r.optJSONObject("deleted") ?: JSONObject()
        val rRem = r.optJSONObject("remember") ?: JSONObject().put("list", JSONArray()).put("updated", 0)
        var n = 0
        // deletions: newest of both sides
        val del = Store.deleted()
        rDel.keys().forEach { k -> del[k] = maxOf(del[k] ?: 0L, rDel.getLong(k)) }
        for (c in Store.convs.toList()) { val d = del[c.id]; if (d != null && d >= c.updated) { Store.dropSynced(c.id); n++; changed += c.id } }
        rChats.keys().asSequence().toList().forEach { id -> val d = del[id]; if (d != null && d >= rChats.getJSONObject(id).optLong("updated")) rChats.remove(id) }
        // projects: newest per id
        val pm = linkedMapOf<String, Project>()
        for (i in 0 until rProjects.length()) { val j = rProjects.getJSONObject(i); val id = j.getString("id"); val u = j.optLong("updated")
            if ((del[id] ?: -1L) >= u) continue; pm[id] = Project(id, j.optString("name", "Project"), j.optString("notes", ""), u) }
        for (lp in Store.projects) { if ((del[lp.id] ?: -1L) >= lp.updated) continue; val rp = pm[lp.id]; if (rp == null || lp.updated >= rp.updated) pm[lp.id] = lp }
        val before = Store.projects.map { "${it.id}|${it.name}|${it.notes}|${it.updated}" }
        val merged = pm.values.sortedByDescending { it.updated }
        if (merged.map { "${it.id}|${it.name}|${it.notes}|${it.updated}" } != before) { Store.replaceProjects(merged); n++ }
        val outProjects = JSONArray(); merged.forEach { outProjects.put(JSONObject().put("id", it.id).put("name", it.name).put("notes", it.notes).put("updated", it.updated)) }
        // chats: bring in newer ones
        for (id in rChats.keys().asSequence().toList()) {
            val rc = rChats.getJSONObject(id); val ru = rc.optLong("updated")
            val lc = Store.convs.firstOrNull { it.id == id }
            if (lc != null && lc.updated >= ru) continue
            val f = file(t, o, "chats/$id.json"); val d = f.data ?: continue
            val old = if (lc != null) Store.msgs(lc).toList() else emptyList()
            val ma = d.optJSONArray("msgs") ?: JSONArray()
            val msgs = (0 until ma.length()).map { i ->
                val m = ma.getJSONObject(i); val txt = m.optString("t"); val prev = old.getOrNull(i)
                val atts = if (prev != null && prev.text == txt && prev.atts.isNotEmpty()) prev.atts else {
                    val a = m.optJSONArray("a") ?: JSONArray()
                    (0 until a.length()).mapNotNull { k -> a.optJSONObject(k)?.optString("name")?.takeIf { it.isNotEmpty() }?.let { Store.SYNCED + it } }
                }
                Msg(m.optBoolean("u"), txt, atts, m.optBoolean("s"), m.optBoolean("e"))
            }
            val proj = if (d.isNull("project")) null else d.optString("project").ifEmpty { null }
            val c = lc ?: Conv(id, d.optString("title", "Chat"), proj, ru)
            c.title = d.optString("title", c.title); c.updated = d.optLong("updated", ru)
            Store.putSynced(c, msgs); n++; changed += id
        }
        // chats: send ours that are newer
        for (c in Store.convs.toList()) {
            if ((del[c.id] ?: -1L) >= c.updated) continue
            val rc = rChats.optJSONObject(c.id)
            if (rc != null && rc.optLong("updated") >= c.updated) continue
            val msgs = Store.msgs(c).toList(); if (msgs.isEmpty()) continue
            val ma = JSONArray()
            msgs.forEach { m ->
                val a = JSONArray(); m.atts.forEach { path -> val name = path.substringAfterLast('/'); a.put(JSONObject().put("name", name).put("mime", guessMime(name))) }
                val j = JSONObject().put("u", m.user).put("t", m.text).put("a", a).put("s", m.stopped); if (m.error) j.put("e", true); ma.put(j)
            }
            val data = JSONObject().put("id", c.id).put("title", c.title).put("project", c.project ?: JSONObject.NULL).put("updated", c.updated).put("msgs", ma)
            val ex = if (rc != null) file(t, o, "chats/${c.id}.json").sha else null
            put(t, o, "chats/${c.id}.json", data, ex)
            rChats.put(c.id, JSONObject().put("title", c.title).put("project", c.project ?: JSONObject.NULL).put("updated", c.updated))
        }
        // remember: newest list wins
        val lAt = p.getLong("rememberAt", 0L)
        val outRem = if (rRem.optLong("updated") > lAt) {
            p.edit().putString("remember", (rRem.optJSONArray("list") ?: JSONArray()).toString()).putLong("rememberAt", rRem.optLong("updated")).apply(); n++; rRem
        } else JSONObject().put("list", JSONArray(Remember.list(p))).put("updated", lAt)
        val cut = System.currentTimeMillis() - 90L * 86_400_000L
        val keep = del.filterValues { it > cut }
        Store.setDeleted(keep)
        val out = JSONObject().put("v", 1).put("chats", rChats).put("projects", outProjects).put("deleted", JSONObject(keep as Map<*, *>)).put("remember", outRem)
        if (idx.data == null || out.toString() != idx.data.toString()) {
            try { put(t, o, "index.json", out, idx.sha) }
            catch (e: HttpErr) { if (retry && (e.code == 409 || e.code == 422)) return n + once(t, p, changed, false); throw e }
        }
        return n
    }

    private fun guessMime(n: String): String {
        val e = n.substringAfterLast('.', "").lowercase()
        return when (e) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
    }
}
