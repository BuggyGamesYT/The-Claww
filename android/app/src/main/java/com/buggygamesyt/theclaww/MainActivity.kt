package com.buggygamesyt.theclaww

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.system.Os
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    // Palette: warm dark, soft surfaces, one accent.
    data class Theme(val name: String, val bg: Int, val surface: Int, val surface2: Int, val fg: Int, val muted: Int, val accent: Int, val onAccent: Int)
    companion object {
        val themes = listOf(
            Theme("Ember", Color.rgb(14, 14, 16), Color.rgb(28, 28, 32), Color.rgb(40, 40, 46), Color.rgb(236, 236, 240), Color.rgb(150, 150, 160), Color.rgb(255, 122, 69), Color.BLACK),
            Theme("Midnight", Color.rgb(10, 14, 26), Color.rgb(22, 30, 50), Color.rgb(34, 44, 70), Color.rgb(228, 234, 248), Color.rgb(140, 152, 180), Color.rgb(110, 160, 255), Color.BLACK),
            Theme("Forest", Color.rgb(12, 20, 16), Color.rgb(24, 36, 30), Color.rgb(36, 52, 44), Color.rgb(228, 240, 232), Color.rgb(140, 165, 150), Color.rgb(110, 210, 140), Color.BLACK),
            Theme("Grape", Color.rgb(18, 12, 26), Color.rgb(34, 24, 48), Color.rgb(50, 36, 68), Color.rgb(240, 232, 250), Color.rgb(165, 150, 185), Color.rgb(190, 130, 255), Color.BLACK),
            Theme("AMOLED", Color.BLACK, Color.rgb(20, 20, 20), Color.rgb(34, 34, 34), Color.WHITE, Color.rgb(140, 140, 140), Color.rgb(255, 255, 255), Color.BLACK),
            Theme("Paper", Color.rgb(246, 244, 240), Color.WHITE, Color.rgb(232, 229, 224), Color.rgb(28, 28, 30), Color.rgb(110, 110, 116), Color.rgb(230, 92, 40), Color.WHITE),
        )
        // Built-in gradient wallpapers (top, bottom).
        val wallpapers = listOf(
            "None" to null,
            "Sunset" to intArrayOf(Color.rgb(255, 120, 80), Color.rgb(90, 30, 110)),
            "Aurora" to intArrayOf(Color.rgb(20, 180, 150), Color.rgb(20, 30, 90)),
            "Ocean" to intArrayOf(Color.rgb(40, 120, 220), Color.rgb(10, 20, 50)),
            "Dusk" to intArrayOf(Color.rgb(120, 80, 200), Color.rgb(240, 120, 150)),
            "Your photo" to null,
        )
        var ollama: Process? = null
        val transcript = mutableListOf<Pair<String, Boolean>>()
    }
    private lateinit var t: Theme
    private val bg get() = t.bg
    private val fg get() = t.fg
    private val muted get() = t.muted
    private val accent get() = t.accent
    private val surface2 get() = t.surface2
    private val hasWall get() = prefs.getString("wall", "None") != "None"
    private val surface get() = if (hasWall) (t.surface and 0x00FFFFFF) or (0xE0 shl 24) else t.surface
    private val good = Color.rgb(80, 200, 120)

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var modelChip: TextView
    private var claw: Process? = null
    private val history = StringBuilder()
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    @Volatile private var alive = true
    @Volatile private var stopped = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private val builtIn get() = prefs.getBoolean("builtin", true)
    private val host get() = if (builtIn) "http://127.0.0.1:11434" else prefs.getString("host", "")!!.trim()
    private val model get() = prefs.getString("model", "llama3.2:1b")!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("claww", Context.MODE_PRIVATE)
        t = themes.firstOrNull { it.name == prefs.getString("theme", "Ember") } ?: themes[0]
        window.statusBarColor = bg
        window.navigationBarColor = bg

        // Header
        val title = TextView(this).apply {
            text = "The Claww"; setTextColor(fg); textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        statusDot = View(this).apply { background = round(muted, 5) }
        statusText = TextView(this).apply { setTextColor(muted); textSize = 13f; text = "Starting…" }
        val status = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(6) })
            addView(statusText)
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title); addView(status)
        }
        modelChip = chip(model) { openSettings() }
        val gear = chip("Settings") { openSettings() }
        val look = chip("Look") { openLook() }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(12))
            addView(titleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(modelChip)
            addView(look, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
            addView(gear, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        }

        // Chat
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        scroll = ScrollView(this).apply { addView(chat); isFillViewport = true }

        // Composer
        input = EditText(this).apply {
            hint = "Message claw"; setHintTextColor(muted); setTextColor(fg)
            background = null; textSize = 16f; maxLines = 6
            setPadding(dp(18), dp(12), dp(8), dp(12))
        }
        sendBtn = TextView(this).apply {
            text = "↑"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(t.onAccent)
            background = round(accent, 22)
            setOnClickListener { onSend() }
        }
        val composer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = round(surface, 28)
            setPadding(0, 0, dp(6), 0)
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        val composerWrap = LinearLayout(this).apply {
            setPadding(dp(14), dp(6), dp(14), dp(14))
            addView(composer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(composerWrap, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        applyWallpaper(root)
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        val isLight = Color.luminance(bg) > 0.5f
        if (isLight) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        if (transcript.isNotEmpty()) { transcript.toList().forEach { bubble(it.first, it.second, false) } } else
        bubble("Hi. I'm claw, running right on this tablet with Ollama.\nOpen Settings to download a model, then ask me anything.", false)
        if (builtIn) startOllama()
        pollStatus()
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(fg)
        background = round(surface2, 16); setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { onClick() }
    }

    private fun bubble(s: String, user: Boolean, record: Boolean = true): TextView {
        if (record) transcript.add(s to user)
        val tv = TextView(this).apply {
            text = s; textSize = 15.5f; setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
            setTextColor(if (user) t.onAccent else fg)
            background = round(if (user) accent else surface, 18)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        }
        val row = LinearLayout(this).apply {
            gravity = if (user) Gravity.END else Gravity.START
            setPadding(0, dp(5), 0, dp(5))
            addView(tv)
        }
        chat.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    // ---------- Built-in Ollama ----------
    private fun startOllama() {
        if (ollama != null) return
        thread {
            try {
                val lib = applicationInfo.nativeLibraryDir
                val work = File(filesDir, "ollama").apply { mkdirs() }
                val payload = File(work, "build/lib/ollama").apply { mkdirs() }
                // Ollama finds its native runtime at <cwd>/build/lib/ollama.
                // Lines are "path<TAB>libname" for native files shipped in jniLibs, or "path" for assets.
                // Executables can only run from nativeLibraryDir, so payload entries are symlinks there.
                assets.open("ollama_payload.txt").bufferedReader().readLines().filter { it.isNotBlank() }.forEach { line ->
                    val parts = line.split('\t')
                    val dest = File(payload, parts[0]); dest.parentFile?.mkdirs()
                    if (parts.size > 1) {
                        dest.delete(); Os.symlink("$lib/${parts[1]}", dest.absolutePath)
                    } else {
                        assets.open("ollama/${parts[0]}").use { i -> dest.outputStream().use { i.copyTo(it) } }
                    }
                }
                val pb = ProcessBuilder("$lib/libollama.so", "serve").directory(work)
                    .redirectErrorStream(true).redirectOutput(File(work, "serve.log"))
                env(pb.environment())
                pb.environment()["OLLAMA_HOST"] = "127.0.0.1:11434"
                pb.environment()["OLLAMA_MODELS"] = File(filesDir, "models").absolutePath
                ollama = pb.start()
            } catch (e: Exception) {
                runOnUiThread { bubble("Couldn't start built-in Ollama: ${e.message}", false) }
            }
        }
    }

    private fun env(e: MutableMap<String, String>) {
        val home = File(filesDir, "home").apply { mkdirs() }
        e["HOME"] = home.absolutePath
        e["TMPDIR"] = cacheDir.absolutePath
        e["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
        e["PATH"] = "/system/bin:/system/xbin:" + (e["PATH"] ?: "")
    }

    private fun pollStatus() = thread {
        while (alive) {
            val ok = try {
                (URL("$host/api/version").openConnection() as HttpURLConnection).run {
                    connectTimeout = 1500; readTimeout = 1500; responseCode == 200
                }
            } catch (_: Exception) { false }
            // Green only when the selected model is actually downloaded.
            val hasModel = ok && try {
                val a = JSONObject(URL("$host/api/tags").readText()).getJSONArray("models")
                val want = if (model.contains(':')) model else "$model:latest"
                (0 until a.length()).any { a.getJSONObject(it).getString("name") == want }
            } catch (_: Exception) { false }
            val amber = Color.rgb(240, 180, 60)
            runOnUiThread {
                statusDot.background = round(if (hasModel) good else if (ok) amber else muted, 5)
                statusText.text = when {
                    hasModel && builtIn -> "Ready · $model on this tablet"
                    hasModel -> "Ready · connected to Ollama"
                    ok -> "Ollama running · $model not downloaded"
                    builtIn -> "Starting Ollama…"
                    else -> "Can't reach Ollama"
                }
            }
            Thread.sleep(3000)
        }
    }

    // ---------- Settings ----------
    private fun openSettings() {
        val pad = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, dp(8)); setBackgroundColor(t.surface) }
        fun label(s: String) = TextView(this).apply { text = s; setTextColor(muted); textSize = 12f; setPadding(0, dp(14), 0, dp(6)) }
        fun field(v: String, h: String) = EditText(this).apply {
            setText(v); hint = h; setTextColor(fg); setHintTextColor(muted); setSingleLine()
            background = round(surface2, 12); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val sw = Switch(this).apply { text = "Run Ollama on this tablet"; setTextColor(fg); isChecked = builtIn }
        val hostF = field(prefs.getString("host", "")!!, "http://192.168.1.50:11434").apply { inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val modelF = field(model, "llama3.2:1b")
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        val pullStatus = TextView(this).apply { setTextColor(muted); textSize = 12f }
        val models = TextView(this).apply { setTextColor(fg); textSize = 13f }
        val pull = Button(this).apply {
            text = "Download model"; setTextColor(t.onAccent); background = round(accent, 12); isAllCaps = false
        }
        box.addView(sw)
        box.addView(label("Remote Ollama host (when not on this tablet)")); box.addView(hostF)
        box.addView(label("Model")); box.addView(modelF)
        box.addView(TextView(this).apply { text = "Small models suit a tablet: llama3.2:1b, qwen2.5-coder:1.5b, gemma3:1b"; setTextColor(muted); textSize = 12f; setPadding(0, dp(6), 0, dp(10)) })
        box.addView(pull, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        box.addView(progress); box.addView(pullStatus)
        box.addView(label("Installed models")); box.addView(models)
        fun save() {
            prefs.edit().putBoolean("builtin", sw.isChecked).putString("host", hostF.text.toString().trim())
                .putString("model", modelF.text.toString().trim()).apply()
            modelChip.text = model
            if (builtIn) startOllama()
        }
        fun refreshModels() = thread {
            val s = try {
                val j = JSONObject(URL("$host/api/tags").readText())
                val a = j.getJSONArray("models")
                (0 until a.length()).joinToString("\n") { a.getJSONObject(it).getString("name") }.ifEmpty { "None yet" }
            } catch (e: Exception) { "Ollama not reachable yet" }
            runOnUiThread { models.text = s }
        }
        pull.setOnClickListener {
            save(); progress.visibility = View.VISIBLE; pull.isEnabled = false
            val name = model
            thread {
                try {
                    val c = URL("$host/api/pull").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(JSONObject().put("name", name).toString().toByteArray()) }
                    c.inputStream.bufferedReader().forEachLine { line ->
                        val j = JSONObject(line)
                        val st = j.optString("status"); val tot = j.optLong("total"); val done = j.optLong("completed")
                        runOnUiThread {
                            pullStatus.text = if (tot > 0) "$st  ${done / 1048576} / ${tot / 1048576} MB" else st
                            if (tot > 0) progress.progress = (done * 100 / tot).toInt()
                            if (j.has("error")) pullStatus.text = j.getString("error")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { pullStatus.text = "Download failed: ${e.message}" }
                }
                runOnUiThread { pull.isEnabled = true; progress.visibility = View.GONE }
                refreshModels()
            }
        }
        refreshModels()
        AlertDialog.Builder(this, if (Color.luminance(t.bg) > 0.5f) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done") { _, _ -> save() }
            .setNeutralButton("New chat") { _, _ -> save(); history.setLength(0); chat.removeAllViews(); transcript.clear(); bubble("New chat. What's next?", false) }
            .show()
    }

    // ---------- Chat via claw ----------
    private fun onSend() {
        // Stop must work even when the input box is empty.
        if (claw != null) {
            stopped = true
            val p = claw
            thread { p?.destroy(); Thread.sleep(800); if (p?.isAlive == true) p.destroyForcibly() }
            return
        }
        val msg = input.text.toString().trim()
        if (msg.isEmpty()) return
        input.setText("")
        bubble(msg, true)
        val out = bubble("…", false)
        sendBtn.text = "■"
        val ctx = if (history.length > 8000) history.substring(history.length - 8000) else history.toString()
        val prompt = if (ctx.isBlank()) msg else "Earlier in this conversation:\n$ctx\n\nNew message from the user:\n$msg"
        stopped = false
        thread {
            val sb = StringBuilder()
            try {
                val work = File(filesDir, "workspace").apply { mkdirs() }
                val pb = ProcessBuilder("${applicationInfo.nativeLibraryDir}/libclaw.so", "--model", model,
                    "--dangerously-skip-permissions", "prompt", prompt).directory(work).redirectErrorStream(true)
                val e = pb.environment(); env(e)
                e["CLAW_CONFIG_HOME"] = File(filesDir, "home/.claw").absolutePath
                e["NO_COLOR"] = "1"; e["TERM"] = "dumb"; e["OLLAMA_HOST"] = host
                val p = pb.start(); claw = p; p.outputStream.close()
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(512)
                    while (true) {
                        val n = r.read(buf); if (n < 0) break
                        sb.append(String(buf, 0, n).replace(ansi, ""))
                        val shown = sb.toString().trim()
                        runOnUiThread { out.text = shown.ifEmpty { "…" }; scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
                    }
                }
                val code = p.waitFor()
                if (!stopped && code != 0 && sb.isBlank()) runOnUiThread { out.text = "claw stopped (code $code). Is a model downloaded?" }
            } catch (ex: Exception) {
                if (!stopped) runOnUiThread { out.text = "Error: ${ex.message}" }
            } finally {
                if (stopped) {
                    val partial = sb.toString().trim()
                    sb.setLength(0); sb.append(partial)
                    runOnUiThread {
                        val note = "Not finished · you stopped this reply"
                        val body = partial.ifEmpty { "" }
                        val full = if (body.isEmpty()) note else "$body\n\n$note"
                        val span = android.text.SpannableString(full)
                        val start = full.length - note.length
                        span.setSpan(android.text.style.ForegroundColorSpan(muted), start, full.length, 0)
                        span.setSpan(android.text.style.StyleSpan(Typeface.ITALIC), start, full.length, 0)
                        span.setSpan(android.text.style.RelativeSizeSpan(0.85f), start, full.length, 0)
                        out.text = span
                    }
                }
                claw = null
                val idx = transcript.indexOfLast { !it.second }
                val saved = if (stopped) (sb.toString().trim() + "\n\n[Not finished · you stopped this reply]").trim() else sb.toString().trim()
                if (idx >= 0 && saved.isNotBlank()) transcript[idx] = saved to false
                history.append("User: ").append(msg).append("\nAssistant: ").append(sb.toString().trim()).append(if (stopped) " [reply cut off by user]" else "").append("\n\n")
                runOnUiThread { sendBtn.text = "↑" }
            }
        }
    }

    // ---------- Themes & wallpapers ----------
    private fun applyWallpaper(root: FrameLayout) {
        val w = prefs.getString("wall", "None")
        val grad = wallpapers.firstOrNull { it.first == w }?.second
        if (grad != null) {
            root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, grad)
        } else if (w == "Your photo") {
            try {
                val uri = Uri.parse(prefs.getString("wallUri", ""))
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (opts.outWidth / sample > 2000 || opts.outHeight / sample > 2000) sample *= 2
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                root.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(bmp) },
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            } catch (_: Exception) { }
        }
        if (w != "None") {
            val dim = prefs.getInt("dim", 45)
            root.addView(View(this).apply { setBackgroundColor((t.bg and 0x00FFFFFF) or ((dim * 255 / 100) shl 24)) },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun openLook() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)); setBackgroundColor(t.surface) }
        fun label(s: String) = TextView(this).apply { text = s; setTextColor(muted); textSize = 12f; setPadding(0, dp(14), 0, dp(8)) }
        fun swatchRow(items: List<Pair<String, GradientDrawable>>, selected: String, pick: (String) -> Unit): View {
            val row = LinearLayout(this)
            items.forEach { (name, d) ->
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), 0, dp(4), 0)
                    val sw = View(this@MainActivity).apply {
                        background = d.apply { cornerRadius = dp(14).toFloat(); if (name == selected) setStroke(dp(3), accent) else setStroke(dp(1), muted) }
                    }
                    addView(sw, LinearLayout.LayoutParams(dp(54), dp(54)))
                    addView(TextView(this@MainActivity).apply { text = name; setTextColor(fg); textSize = 11f; setPadding(0, dp(4), 0, 0) })
                    setOnClickListener { pick(name) }
                }
                row.addView(col)
            }
            return android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        }
        var dialog: AlertDialog? = null
        box.addView(label("Theme"))
        box.addView(swatchRow(themes.map { th ->
            th.name to GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(th.surface, th.accent))
        }, t.name) { prefs.edit().putString("theme", it).apply(); dialog?.dismiss(); recreate() })
        box.addView(label("Wallpaper"))
        box.addView(swatchRow(wallpapers.map { (name, g) ->
            name to (if (g != null) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, g)
                     else GradientDrawable().apply { setColor(if (name == "None") t.bg else t.surface2) })
        }, prefs.getString("wall", "None")!!) { name ->
            dialog?.dismiss()
            if (name == "Your photo") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, 42)
            } else { prefs.edit().putString("wall", name).apply(); recreate() }
        })
        box.addView(label("Wallpaper dimming"))
        box.addView(android.widget.SeekBar(this).apply {
            max = 90; progress = prefs.getInt("dim", 45)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, user: Boolean) {}
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) { prefs.edit().putInt("dim", sb.progress).apply(); dialog?.dismiss(); recreate() }
            })
        })
        val style = if (Color.luminance(t.bg) > 0.5f) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert
        dialog = AlertDialog.Builder(this, style).setTitle("Look").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 42 && resultCode == RESULT_OK && uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.edit().putString("wall", "Your photo").putString("wallUri", uri.toString()).apply()
            recreate()
        }
    }

    override fun onDestroy() {
        alive = false; claw?.destroy(); if (isFinishing) { ollama?.destroy(); ollama = null }; super.onDestroy()
    }
}
