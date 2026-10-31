package com.buggygamesyt.theclaww

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var host: EditText
    private lateinit var model: EditText
    private lateinit var send: Button
    private var proc: Process? = null
    private val history = StringBuilder()
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("claww", Context.MODE_PRIVATE)
        val pad = (12 * resources.displayMetrics.density).toInt()

        host = EditText(this).apply {
            hint = "Ollama host, e.g. http://192.168.1.50:11434"
            setText(prefs.getString("host", ""))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        model = EditText(this).apply {
            hint = "Model, e.g. qwen2.5-coder:7b"
            setText(prefs.getString("model", "qwen2.5-coder:7b"))
            setSingleLine()
        }
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(Color.rgb(220, 220, 220))
            text = "The Claww - claw v0.1.9 running on your device.\nSet your Ollama host and model, then ask anything.\n\n"
        }
        scroll = ScrollView(this).apply { addView(log) }
        input = EditText(this).apply {
            hint = "Ask claw..."
            minLines = 1
            maxLines = 5
        }
        send = Button(this).apply { text = "Send" }
        val stop = Button(this).apply { text = "Stop" }
        val clear = Button(this).apply { text = "New chat" }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(send); addView(stop)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(model, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clear)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(18, 18, 22))
            addView(host, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        listOf(host, model, input).forEach { it.setTextColor(Color.WHITE); it.setHintTextColor(Color.GRAY) }
        setContentView(root)

        send.setOnClickListener {
            val msg = input.text.toString().trim()
            if (msg.isEmpty() || proc != null) return@setOnClickListener
            prefs.edit().putString("host", host.text.toString().trim())
                .putString("model", model.text.toString().trim()).apply()
            input.setText("")
            run(msg)
        }
        stop.setOnClickListener { proc?.destroy() }
        clear.setOnClickListener { history.setLength(0); log.text = "New chat.\n\n" }
    }

    private fun append(s: String) = runOnUiThread {
        log.append(s)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun run(msg: String) {
        val bin = File(applicationInfo.nativeLibraryDir, "libclaw.so")
        val work = File(filesDir, "workspace").apply { mkdirs() }
        val home = File(filesDir, "home").apply { mkdirs() }
        val ctx = if (history.length > 8000) history.substring(history.length - 8000) else history.toString()
        val prompt = if (ctx.isBlank()) msg else
            "Earlier in this conversation:\n$ctx\n\nNew message from the user:\n$msg"
        append("> $msg\n")
        send.isEnabled = false
        thread {
            val out = StringBuilder()
            try {
                val pb = ProcessBuilder(
                    bin.absolutePath, "--model", model.text.toString().trim(),
                    "--dangerously-skip-permissions", "prompt", prompt
                ).directory(work).redirectErrorStream(true)
                val env = pb.environment()
                env["HOME"] = home.absolutePath
                env["CLAW_CONFIG_HOME"] = File(home, ".claw").absolutePath
                env["TMPDIR"] = cacheDir.absolutePath
                env["PATH"] = "/system/bin:/system/xbin:" + (env["PATH"] ?: "")
                env["NO_COLOR"] = "1"
                env["TERM"] = "dumb"
                env["OLLAMA_HOST"] = host.text.toString().trim()
                val p = pb.start()
                proc = p
                p.outputStream.close()
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(1024)
                    while (true) {
                        val n = r.read(buf); if (n < 0) break
                        val chunk = String(buf, 0, n).replace(ansi, "")
                        out.append(chunk); append(chunk)
                    }
                }
                val code = p.waitFor()
                if (code != 0) append("\n[claw exited with code $code]")
            } catch (e: Exception) {
                append("\n[error: ${e.message}]")
            } finally {
                proc = null
                history.append("User: ").append(msg).append("\nAssistant: ").append(out.toString().trim()).append("\n\n")
                append("\n\n")
                runOnUiThread { send.isEnabled = true }
            }
        }
    }

    override fun onDestroy() { proc?.destroy(); super.onDestroy() }
}
