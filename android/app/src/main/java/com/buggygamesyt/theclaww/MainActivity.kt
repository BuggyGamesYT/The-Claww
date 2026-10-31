package com.buggygamesyt.theclaww

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
    private val bg = Color.rgb(14, 14, 16)
    private val surface = Color.rgb(28, 28, 32)
    private val surface2 = Color.rgb(40, 40, 46)
    private val fg = Color.rgb(236, 236, 240)
    private val muted = Color.rgb(150, 150, 160)
    private val accent = Color.rgb(255, 122, 69)
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
    private var ollama: Process? = null
    private val history = StringBuilder()
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    @Volatile private var alive = true

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private val builtIn get() = prefs.getBoolean("builtin", true)
    private val host get() = if (builtIn) "http://127.0.0.1:11434" else prefs.getString("host", "")!!.trim()
    private val model get() = prefs.getString("model", "llama3.2:1b")!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("claww", Context.MODE_PRIVATE)
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
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(12))
            addView(titleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(modelChip); addView(gear, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
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
            text = "↑"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.BLACK)
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(composerWrap, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(root)

        bubble("Hi. I'm claw, running right on this tablet with Ollama.\nOpen Settings to download a model, then ask me anything.", false)
        if (builtIn) startOllama()
        pollStatus()
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(fg)
        background = round(surface2, 16); setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { onClick() }
    }

    private fun bubble(s: String, user: Boolean): TextView {
        val tv = TextView(this).apply {
            text = s; textSize = 15.5f; setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
            setTextColor(if (user) Color.BLACK else fg)
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
                assets.open("ollama_payload.txt").bufferedReader().readLines().filter { it.isNotBlank() }.forEach { p ->
                    val dest = File(payload, p); dest.parentFile?.mkdirs()
                    val name = File(p).name
                    if (name.startsWith("lib") && name.endsWith(".so")) {
                        dest.delete(); Os.symlink("$lib/$name", dest.absolutePath)
                    } else {
                        assets.open("ollama/$p").use { i -> dest.outputStream().use { i.copyTo(it) } }
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
            runOnUiThread {
                statusDot.background = round(if (ok) good else muted, 5)
                statusText.text = when {
                    ok && builtIn -> "Ollama running on this tablet"
                    ok -> "Connected to Ollama"
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
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, dp(8)); setBackgroundColor(surface) }
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
            text = "Download model"; setTextColor(Color.BLACK); background = round(accent, 12); isAllCaps = false
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done") { _, _ -> save() }
            .setNeutralButton("New chat") { _, _ -> save(); history.setLength(0); chat.removeAllViews(); bubble("New chat. What's next?", false) }
            .show()
    }

    // ---------- Chat via claw ----------
    private fun onSend() {
        val msg = input.text.toString().trim()
        if (msg.isEmpty()) return
        if (claw != null) { claw?.destroy(); return }
        input.setText("")
        bubble(msg, true)
        val out = bubble("…", false)
        sendBtn.text = "■"
        val ctx = if (history.length > 8000) history.substring(history.length - 8000) else history.toString()
        val prompt = if (ctx.isBlank()) msg else "Earlier in this conversation:\n$ctx\n\nNew message from the user:\n$msg"
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
                if (code != 0 && sb.isBlank()) runOnUiThread { out.text = "claw stopped (code $code). Is a model downloaded?" }
            } catch (ex: Exception) {
                runOnUiThread { out.text = "Error: ${ex.message}" }
            } finally {
                claw = null
                history.append("User: ").append(msg).append("\nAssistant: ").append(sb.toString().trim()).append("\n\n")
                runOnUiThread { sendBtn.text = "↑" }
            }
        }
    }

    override fun onDestroy() {
        alive = false; claw?.destroy(); ollama?.destroy(); super.onDestroy()
    }
}
