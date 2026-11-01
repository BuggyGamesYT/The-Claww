package com.buggygamesyt.theclaww

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A model family from a catalog (Ollama library or Hugging Face). */
data class CatalogEntry(val id: String, val source: String, val title: String, val desc: String, val caps: List<String>, val stat: String, val date: Long = 0, val downloads: Long = 0)
/** One downloadable build of a model: what to pass to `ollama pull`, a label and its size. */
data class Variant(val pull: String, val label: String, val bytes: Long, val note: String = "")
data class Suggestion(val pull: String, val bytes: Long, val why: String, val minRamGb: Int)

/** Free model catalogs: the Ollama library and every GGUF model on Hugging Face (pulled via hf.co/<repo>:<quant>). */
object Catalog {
    val suggestions = listOf(
        Suggestion("gemma3:1b", 815_000_000, "Tiny and quick, good for simple chat", 0),
        Suggestion("llama3.2:3b", 2_000_000_000, "Fast all-rounder", 0),
        Suggestion("qwen2.5-coder:3b", 1_900_000_000, "Coding help on small devices", 0),
        Suggestion("qwen3:4b", 2_500_000_000, "Thinks step by step, uses tools", 6),
        Suggestion("gemma3:4b", 3_300_000_000, "Sees photos and videos", 6),
        Suggestion("qwen2.5vl:3b", 3_200_000_000, "Vision: reads images and screenshots", 6),
        Suggestion("phi4-mini", 2_500_000_000, "Strong reasoning for its size", 6),
        Suggestion("llama3.1:8b", 4_900_000_000, "Smarter general model", 12),
        Suggestion("qwen2.5-coder:7b", 4_700_000_000, "Best coding model that fits 12–16 GB", 12),
        Suggestion("qwen2.5-coder:14b", 9_000_000_000, "Big coder for 24 GB+ machines", 24),
    )

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "TheClaww/0.2 (Android)")
        c.setRequestProperty("Accept", "text/html,application/json")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").trim()

    /** Ollama library search (empty query = most popular). Cloud-only models are skipped: they can't be downloaded. */
    fun ollama(q: String, page: Int, newest: Boolean = true): Pair<List<CatalogEntry>, Boolean> {
        val html = get("https://ollama.com/search?q=${enc(q)}&p=${page + 1}" + if (newest) "&o=newest" else "&o=popular")
        val more = html.contains("data-has-more=\"true\"")
        val out = mutableListOf<CatalogEntry>()
        html.split("<a href=\"/library/").drop(1).forEach { block ->
            val name = block.substringBefore('"')
            if (name.isEmpty() || name.contains('/') || name.contains(':')) return@forEach
            val body = block.substringBefore("</li>")
            val desc = Regex("<p class=\"mt-1[^>]*>([^<]*)</p>").find(body)?.groupValues?.get(1)?.let(::unescape) ?: ""
            val caps = Regex("text-indigo-600[^>]*>([^<]+)<").findAll(body).map { it.groupValues[1].trim() }.toList()
            val sizes = Regex("bg-\\[#ddf4ff\\][^>]*>([^<]+)<").findAll(body).map { it.groupValues[1].trim() }.toList()
            val cloud = body.contains(">cloud<")
            if (sizes.isEmpty() && cloud) return@forEach
            val dl = Regex("title=\"([\\d,]+) downloads\"").find(body)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
            val stat = listOfNotNull(dl?.let { "${short(it)} pulls" }, sizes.takeIf { it.isNotEmpty() }?.joinToString(" · ")).joinToString(" · ")
            val date = Regex("title=\"Updated ([A-Za-z]+ \\d+, \\d{4})\"").find(body)?.groupValues?.get(1)?.let {
                try { java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US).parse(it)?.time } catch (_: Exception) { null } } ?: 0L
            out += CatalogEntry(name, "ollama", name, desc, caps, stat, date, dl ?: 0)
        }
        return out to more
    }

    /** Hugging Face: every public GGUF model (runs in Ollama), most downloaded first. */
    /** newest = trending GGUF uploads ordered by release date (pure "createdAt" is mostly 0-download noise). */
    fun hf(q: String, page: Int, newest: Boolean = true): Pair<List<CatalogEntry>, Boolean> {
        val per = 30
        val json = JSONArray(get("https://huggingface.co/api/models?search=${enc(q)}&filter=gguf&sort=${if (newest) "trendingScore" else "likes"}&direction=-1&limit=${per * (page + 1)}"))
        val out = mutableListOf<CatalogEntry>()
        for (i in page * per until json.length()) {
            val o = json.getJSONObject(i)
            val id = o.getString("id")
            val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val caps = buildList {
                if (o.optString("pipeline_tag") == "image-text-to-text" || tags.any { it == "vision" || it == "multimodal" }) add("vision")
                if (tags.any { it.contains("code", true) }) add("code")
                if (tags.any { it.contains("reasoning", true) || it.contains("thinking", true) }) add("thinking")
            }
            val base = tags.firstOrNull { it.startsWith("base_model:") && !it.contains(":quantized:") && !it.contains(":finetune:") }?.substringAfter("base_model:")
            out += CatalogEntry(id, "hf", id.substringAfter('/'), (base?.let { "Based on $it" } ?: "") + " · by ${id.substringBefore('/')}".let { if (base == null) it.removePrefix(" · ") else it },
                caps, "${short(o.optLong("downloads"))} downloads · ${short(o.optLong("likes"))} likes", isoTime(o.optString("createdAt")), o.optLong("downloads"))
        }
        if (newest) out.sortByDescending { it.date }
        return out to (json.length() >= per * (page + 1))
    }

    fun variants(e: CatalogEntry): List<Variant> = if (e.source == "ollama") ollamaVariants(e.id) else hfVariants(e.id)

    private fun ollamaVariants(name: String): List<Variant> {
        val html = get("https://ollama.com/library/${enc(name)}/tags")
        val seen = HashSet<String>()
        return Regex("href=\"/library/([^\"]+:[^\"]+)\" class=\"md:hidden[\\s\\S]*?</span>\\s*•\\s*([\\d.]+\\s*[KMGT]?B)\\s*•\\s*([^<•]*)").findAll(html).mapNotNull { m ->
            val tag = m.groupValues[1]
            if (!seen.add(tag)) return@mapNotNull null
            Variant(tag, tag.substringAfter(':'), parseSize(m.groupValues[2]), m.groupValues[3].trim().removeSuffix("window").trim())
        }.toList()
    }

    private val quantRe = Regex("(?i)(?:^|[-_.])((?:I?Q\\d(?:_[A-Z0-9]+)*)|BF16|F16|F32)(?=[-_.])")
    private fun hfVariants(repo: String): List<Variant> {
        val files = JSONArray(get("https://huggingface.co/api/models/$repo/tree/main?recursive=true"))
        val byQuant = LinkedHashMap<String, Long>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val path = f.optString("path")
            if (f.optString("type") != "file" || !path.endsWith(".gguf", true) || path.contains("mmproj", true)) continue
            val q = quantRe.find(path.substringAfterLast('/'))?.groupValues?.get(1)?.uppercase() ?: continue
            byQuant[q] = (byQuant[q] ?: 0L) + f.optLong("size", f.optJSONObject("lfs")?.optLong("size") ?: 0L)
        }
        return byQuant.entries.sortedBy { it.value }.map { (q, b) ->
            Variant("hf.co/$repo:$q", q, b, when { q.startsWith("Q4_K_M") -> "recommended"; q.startsWith("Q8") || q.contains("F16") -> "highest quality"; else -> "" })
        }
    }

    fun isoTime(s: String): Long = try { java.time.Instant.parse(s).toEpochMilli() } catch (_: Exception) { 0L }
    fun age(t: Long): String {
        if (t <= 0) return ""
        val d = (System.currentTimeMillis() - t) / 86_400_000L
        return when { d < 1 -> "today"; d < 2 -> "yesterday"; d < 30 -> "${d}d ago"; d < 365 -> "${d / 30}mo ago"; else -> "${d / 365}y ago" }
    }

    /** Models released since the last check (first run just records what exists). Saves what it has seen. */
    fun checkNew(ctx: Context): List<CatalogEntry> {
        val prefs = ctx.getSharedPreferences("claww", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seenModels", emptySet())!!.toMutableSet()
        val first = seen.isEmpty()
        val latest = (try { ollama("", 0, true).first } catch (_: Exception) { emptyList() }) +
            (try { hf("", 0, true).first.filter { it.downloads >= 1000 } } catch (_: Exception) { emptyList() })
        val fresh = latest.filter { it.source + ":" + it.id !in seen }
        seen += latest.map { it.source + ":" + it.id }
        prefs.edit().putStringSet("seenModels", seen.toList().takeLast(3000).toSet()).putLong("lastModelCheck", System.currentTimeMillis())
            .putStringSet("newModels", (prefs.getStringSet("newModels", emptySet())!! + fresh.map { it.source + ":" + it.id }).toList().takeLast(200).toSet()).apply()
        return if (first) emptyList() else fresh
    }

    /** Check every 6 hours while the app's background service runs; notify about new releases. */
    fun startWatcher(ctx: Context) {
        val app = ctx.applicationContext
        if (watcher?.isAlive == true) return
        watcher = Thread {
            while (true) {
                val prefs = app.getSharedPreferences("claww", Context.MODE_PRIVATE)
                if (prefs.getBoolean("watchModels", true) && System.currentTimeMillis() - prefs.getLong("lastModelCheck", 0) > 6 * 3600_000L) {
                    val fresh = try { checkNew(app) } catch (_: Exception) { emptyList() }
                    if (fresh.isNotEmpty()) try {
                        val nm = app.getSystemService(NotificationManager::class.java)
                        nm.createNotificationChannel(NotificationChannel("claww-new", "New AI models", NotificationManager.IMPORTANCE_DEFAULT))
                        val open = PendingIntent.getActivity(app, 3, Intent(app, MainActivity::class.java).setAction("models"), PendingIntent.FLAG_IMMUTABLE)
                        nm.notify(3, Notification.Builder(app, "claww-new").setSmallIcon(android.R.drawable.star_on)
                            .setContentTitle("${fresh.size} new AI model${if (fresh.size == 1) "" else "s"} available")
                            .setContentText(fresh.take(4).joinToString(", ") { it.title }).setContentIntent(open).setAutoCancel(true).build())
                    } catch (_: Exception) {}
                }
                try { Thread.sleep(30 * 60_000L) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }
    private var watcher: Thread? = null

    fun parseSize(s: String): Long {
        val m = Regex("([\\d.]+)\\s*([KMGT]?)B").find(s) ?: return 0
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0
        return (v * when (m.groupValues[2]) { "K" -> 1e3; "M" -> 1e6; "G" -> 1e9; "T" -> 1e12; else -> 1.0 }).toLong()
    }
    fun short(n: Long) = when { n >= 1_000_000_000 -> "%.1fB".format(n / 1e9); n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 1_000 -> "%.1fK".format(n / 1e3); else -> n.toString() }
    fun gb(b: Long) = if (b >= 1e9) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)
}

/**
 * Background model downloads. Runs `/api/pull` one model at a time, keeps the CPU and Wi-Fi awake with
 * wake/wifi locks and the foreground service, so downloads continue with the screen off or the app closed.
 */
object Downloads {
    data class Job(val pull: String, var status: String = "Queued", var done: Long = 0, var total: Long = 0, var state: String = "queued")
    val jobs = mutableListOf<Job>()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private var worker: Thread? = null
    private var cancelCurrent: (() -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    fun listen(f: () -> Unit) { listeners += f }
    fun unlisten(f: () -> Unit) { listeners -= f }
    private fun notifyUi() = main.post { listeners.toList().forEach { it() } }

    @Synchronized fun enqueue(ctx: Context, host: String, pulls: List<String>) {
        pulls.forEach { p -> if (jobs.none { it.pull == p && it.state in setOf("queued", "running") }) jobs += Job(p) }
        notifyUi()
        if (worker?.isAlive != true) start(ctx.applicationContext, host)
    }

    fun cancel(pull: String) {
        synchronized(this) {
            val j = jobs.firstOrNull { it.pull == pull && it.state in setOf("queued", "running") } ?: return
            if (j.state == "running") cancelCurrent?.invoke()
            j.state = "cancelled"; j.status = "Cancelled"
        }
        notifyUi()
    }
    fun clearFinished() { synchronized(this) { jobs.removeAll { it.state in setOf("done", "failed", "cancelled") } }; notifyUi() }

    private fun start(ctx: Context, host: String) {
        try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(Intent(ctx, ClawService::class.java)) } catch (_: Exception) {}
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claww:download").apply { setReferenceCounted(false) }
        val wifi = (ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL, "claww:download").apply { setReferenceCounted(false) }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("claww-dl", "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun note(title: String, text: String, pct: Int, ongoing: Boolean) {
            try {
                nm.notify(2, Notification.Builder(ctx, "claww-dl").setContentTitle(title).setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true)
                    .apply { if (ongoing) setProgress(100, pct, pct < 0) }.build())
            } catch (_: Exception) {}
        }
        worker = Thread {
            wake.acquire(6 * 60 * 60 * 1000L); try { wifi.acquire() } catch (_: Exception) {}
            var ok = 0; var failed = 0
            try {
                while (true) {
                    val job = synchronized(this) { jobs.firstOrNull { it.state == "queued" }?.also { it.state = "running"; it.status = "Starting" } } ?: break
                    notifyUi()
                    var attempt = 0
                    while (job.state == "running") {
                        try {
                            val c = URL("$host/api/pull").openConnection() as HttpURLConnection
                            cancelCurrent = { try { c.disconnect() } catch (_: Exception) {} }
                            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 10 * 60 * 1000
                            c.setRequestProperty("Content-Type", "application/json")
                            c.outputStream.use { it.write(JSONObject().put("name", job.pull).put("stream", true).toString().toByteArray()) }
                            var lastUi = 0L
                            c.inputStream.bufferedReader().forEachLine { line ->
                                val j = try { JSONObject(line) } catch (_: Exception) { return@forEachLine }
                                if (j.has("error")) throw RuntimeException(j.getString("error"))
                                job.status = j.optString("status"); job.total = j.optLong("total", job.total); job.done = j.optLong("completed", job.done)
                                if (job.status == "success") job.state = "done"
                                val now = System.currentTimeMillis()
                                if (now - lastUi > 500) {
                                    lastUi = now; notifyUi()
                                    val pct = if (job.total > 0) (job.done * 100 / job.total).toInt() else -1
                                    note("Downloading ${job.pull}", if (job.total > 0) "${Catalog.gb(job.done)} of ${Catalog.gb(job.total)}" else job.status, pct, true)
                                }
                            }
                            if (job.state == "running") job.state = "done"
                        } catch (e: Exception) {
                            if (job.state == "cancelled") break
                            // Network drops (e.g. Wi-Fi handover while asleep): Ollama resumes partial downloads, so retry.
                            if (++attempt <= 5 && e !is RuntimeException) { job.status = "Connection lost, retrying ($attempt/5)"; notifyUi(); Thread.sleep(5000L * attempt); continue }
                            job.state = "failed"; job.status = e.message ?: "Failed"
                        }
                    }
                    cancelCurrent = null
                    if (job.state == "done") { ok++; job.status = "Downloaded" } else if (job.state == "failed") failed++
                    notifyUi(); main.post { onFinished?.invoke() }
                }
            } finally {
                try { wake.release() } catch (_: Exception) {}; try { wifi.release() } catch (_: Exception) {}
                if (ok + failed > 0) note("Model downloads finished", "$ok downloaded" + if (failed > 0) ", $failed failed" else "", 100, false)
                else nm.cancel(2)
                notifyUi()
            }
        }.apply { isDaemon = true; start() }
    }
}
