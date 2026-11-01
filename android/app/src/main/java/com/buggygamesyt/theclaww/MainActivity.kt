package com.buggygamesyt.theclaww

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.system.Os
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        // Home widgets the user can add.
        val widgetTypes = listOf(
            "status" to "Ollama status",
            "device" to "Device & chip",
            "clock" to "Clock",
            "recent" to "Recent chats",
            "projects" to "Projects",
            "prompt" to "Quick prompt",
            "note" to "Note",
        )
        var ollama: Process? = null
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
    private val amber = Color.rgb(240, 180, 60)

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var pager: Pager
    private val tabViews = mutableListOf<TextView>()
    private lateinit var chatPane: ChatPane
    private lateinit var projPane: ChatPane
    private lateinit var projFrame: FrameLayout
    private lateinit var projList: LinearLayout
    private lateinit var projDetail: LinearLayout
    private var openProject: Project? = null
    private lateinit var homeList: LinearLayout
    private val liveUpdaters = mutableListOf<() -> Unit>()
    private val statusViews = mutableListOf<Pair<View, TextView>>()
    private var statusColor = Color.GRAY
    private var statusLine = "Starting…"
    private var pickingPane: ChatPane? = null
    @Volatile private var alive = true
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private val builtIn get() = prefs.getBoolean("builtin", true)
    private val host get() = if (builtIn) "http://127.0.0.1:11434" else prefs.getString("host", "")!!.trim()
    private val model get() = prefs.getString("model", "llama3.2:1b")!!
    private val dialogStyle get() = if (Color.luminance(t.bg) > 0.5f) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("claww", Context.MODE_PRIVATE)
        Store.init(filesDir)
        val def = if (Build.VERSION.SDK_INT >= 31) "Material You" else "Ember"
        t = allThemes().firstOrNull { it.name == prefs.getString("theme", def) } ?: themes[0]
        window.statusBarColor = bg
        window.navigationBarColor = bg

        // Top bar: settings (with status dot) · Home | Chat | Projects · look
        val tabs = LinearLayout(this).apply {
            background = panel(24); setPadding(dp(4), dp(4), dp(4), dp(4))
            listOf("Home", "Chat", "Projects").forEachIndexed { i, name ->
                val tv = TextView(this@MainActivity).apply {
                    text = name; textSize = 15f; gravity = Gravity.CENTER
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    setOnClickListener { pager.go(i) }
                }
                tabViews.add(tv); addView(tv)
            }
        }
        val settingsBtn = FrameLayout(this).apply {
            background = panel(20)
            addView(TextView(this@MainActivity).apply { text = "⚙"; textSize = 18f; setTextColor(fg); gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val dot = View(this@MainActivity).apply { background = round(muted, 5) }
            addView(dot, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); rightMargin = dp(4) })
            statusViews.add(dot to TextView(this@MainActivity))
            setOnClickListener { openSettings() }
        }
        val lookBtn = FrameLayout(this).apply {
            background = panel(20)
            addView(TextView(this@MainActivity).apply { text = "◐"; textSize = 18f; setTextColor(fg); gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setOnClickListener { openLook() }
        }
        val top = FrameLayout(this).apply {
            setPadding(dp(14), dp(12), dp(14), dp(8))
            addView(settingsBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(tabs, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
            addView(lookBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
        }

        // Pages: Home (left) · Chat (middle) · Projects (right)
        pager = Pager(this)
        pager.addPage(buildHome())
        chatPane = ChatPane(null)
        pager.addPage(chatPane.root)
        pager.addPage(buildProjects())
        pager.onChange = { i -> styleTabs(i); hideKeyboard(); if (i == 0) refreshHome(); if (i == 2 && openProject == null) showProjectList() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        applyWallpaper(root)
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        if (Color.luminance(bg) > 0.5f) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        // Restore last regular chat and the tab the user was on.
        val last = Store.convs.firstOrNull { it.id == prefs.getString("curChat", "") && it.project == null }
        chatPane.open(last ?: Store.newConv(null))
        val startTab = prefs.getInt("tab", 1)
        pager.go(startTab, false); styleTabs(startTab)
        prefs.getString("curProject", null)?.let { pid -> Store.project(pid)?.let { showProjectDetail(it) } }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        // Keeps the app (and the Ollama/claw processes) alive in the background.
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, ClawService::class.java)) } catch (_: Exception) {}
        if (builtIn) startOllama()
        pollStatus()
        if (intent?.action == "models") openModels()
    }

    /** Material You: palette taken from the system's wallpaper-based dynamic colors (Android 12+), following dark/light mode. */
    private fun materialYou(): Theme? {
        if (Build.VERSION.SDK_INT < 31) return null
        fun c(id: Int) = getColor(id)
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (dark) Theme("Material You",
            c(android.R.color.system_neutral1_900), c(android.R.color.system_neutral2_800), c(android.R.color.system_neutral2_700),
            c(android.R.color.system_neutral1_50), c(android.R.color.system_neutral2_300),
            c(android.R.color.system_accent1_200), c(android.R.color.system_accent1_800))
        else Theme("Material You",
            c(android.R.color.system_neutral1_10), c(android.R.color.system_neutral2_50), c(android.R.color.system_accent2_100),
            c(android.R.color.system_neutral1_900), c(android.R.color.system_neutral2_600),
            c(android.R.color.system_accent1_600), c(android.R.color.system_accent1_0))
    }

    private fun allThemes(): List<Theme> = listOfNotNull(materialYou()) + themes

    private fun styleTabs(sel: Int) {
        prefs.edit().putInt("tab", sel).apply()
        tabViews.forEachIndexed { i, tv ->
            tv.background = if (i == sel) round(fg, 20) else null
            tv.setTextColor(if (i == sel) bg else muted)
            tv.typeface = if (i == sel) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(fg)
        background = round(surface2, 16); setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { onClick() }
    }

    // ---------- Glass & bubble opacity ----------
    private val glassOn get() = prefs.getBoolean("glass", false)
    /** "Liquid" = refracting lens glass (Android 13+); "Frosted" = the original translucent tint look. */
    private val liquidOn get() = glassOn && prefs.getString("glassStyle", "Liquid") == "Liquid" && LiquidGlass.supported
    private val glassTints get() = listOf(
        "Clear" to Color.WHITE, "Theme" to t.accent, "Blue" to Color.rgb(90, 160, 255), "Mint" to Color.rgb(80, 220, 180),
        "Pink" to Color.rgb(255, 120, 190), "Violet" to Color.rgb(170, 120, 255), "Amber" to Color.rgb(255, 190, 80), "Smoke" to Color.rgb(20, 20, 24), "Custom" to prefs.getInt("glassCustom", Color.rgb(120, 200, 255)))
    private fun glassTint() = glassTints.firstOrNull { it.first == prefs.getString("glassTint", "Clear") }?.second ?: Color.WHITE
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Liquid glass: translucent tint, a soft top sheen and a bright hairline rim. */
    private fun glass(r: Int, tint: Int, strength: Int = prefs.getInt("glassStrength", 30)): android.graphics.drawable.Drawable {
        val rad = dp(r).toFloat()
        if (liquidOn && Build.VERSION.SDK_INT >= 33) return LiquidGlass(rad, tint, strength / 100f * 0.5f, resources.displayMetrics.density)
        val fill = GradientDrawable().apply { setColor(withAlpha(tint, 20 + strength * 2)); cornerRadius = rad }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(Color.WHITE, 60), withAlpha(Color.WHITE, 8), withAlpha(Color.WHITE, 0))).apply { cornerRadius = rad }
        val rim = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = rad; setStroke(dp(1), withAlpha(Color.WHITE, 90)) }
        return android.graphics.drawable.LayerDrawable(arrayOf(fill, sheen, rim))
    }

    /** Background for cards, bars and buttons: glass when on, otherwise the theme surface. */
    private fun panel(r: Int): android.graphics.drawable.Drawable = if (glassOn) glass(r, glassTint()) else round(surface, r)

    /** Chat bubble background honoring the bubble opacity slider (0–100). */
    private fun bubbleBg(user: Boolean): android.graphics.drawable.Drawable {
        val op = prefs.getInt("bubbleOpacity", 100)
        if (glassOn) return glass(18, if (user) accent else glassTint(), prefs.getInt("glassStrength", 30) * op / 100)
        return round(withAlpha(if (user) accent else t.surface, op * 255 / 100), 18)
    }
    private fun bubbleFg(user: Boolean) = if (user && !glassOn && prefs.getInt("bubbleOpacity", 100) >= 60) t.onAccent else fg

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = panel(22)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun cardLp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun ago(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60000
        return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms)) }
    }

    private fun ask(title: String, hint: String, initial: String = "", multi: Boolean = false, onOk: (String) -> Unit) {
        val f = EditText(this).apply {
            setText(initial); this.hint = hint; setTextColor(fg); setHintTextColor(muted)
            if (!multi) setSingleLine() else { minLines = 3; gravity = Gravity.TOP }
            background = round(surface2, 12); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(12), dp(20), 0); addView(f) }
        AlertDialog.Builder(this, dialogStyle).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(f.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    // ===================== Home =====================
    private fun buildHome(): View {
        homeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        refreshHome()
        return ScrollView(this).apply { addView(homeList); isFillViewport = true }
    }

    private fun widgets(): MutableList<JSONObject> {
        val raw = prefs.getString("widgets", null)
        if (raw == null) return mutableListOf(JSONObject().put("type", "status"), JSONObject().put("type", "device"), JSONObject().put("type", "recent"))
        val a = JSONArray(raw); return (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
    }

    private fun saveWidgets(w: List<JSONObject>) { prefs.edit().putString("widgets", JSONArray(w).toString()).apply() }

    private fun refreshHome() {
        homeList.removeAllViews(); liveUpdaters.clear()
        val list = widgets()
        list.forEachIndexed { i, w ->
            val v = buildWidget(w)
            v.setOnLongClickListener {
                AlertDialog.Builder(this, dialogStyle).setTitle(widgetTypes.firstOrNull { it.first == w.optString("type") }?.second ?: "Panel")
                    .setItems(arrayOf("Move up", "Move down", "Remove")) { _, which ->
                        val l = widgets()
                        when (which) {
                            0 -> if (i > 0) { val x = l.removeAt(i); l.add(i - 1, x) }
                            1 -> if (i < l.size - 1) { val x = l.removeAt(i); l.add(i + 1, x) }
                            2 -> l.removeAt(i)
                        }
                        saveWidgets(l); refreshHome()
                    }.show(); true
            }
            homeList.addView(v, cardLp())
        }
        val add = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = panel(22); setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(Glyph(this@MainActivity, "plus", fg), LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(10) })
            addView(text("Add a widget", 15f, fg))
            setOnClickListener { addWidget() }
        }
        homeList.addView(add, cardLp())
        homeList.addView(text("Long-press a widget to move or remove it. Swipe left or right to switch tabs.", 12f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        liveUpdaters.forEach { it() }
    }

    private fun addWidget() {
        AlertDialog.Builder(this, dialogStyle).setTitle("Add a widget")
            .setItems(widgetTypes.map { it.second }.toTypedArray()) { _, which ->
                val type = widgetTypes[which].first
                fun add(o: JSONObject) { val l = widgets(); l.add(o); saveWidgets(l); refreshHome() }
                when (type) {
                    "prompt" -> ask("Quick prompt", "e.g. Summarize what we did today") { if (it.isNotEmpty()) add(JSONObject().put("type", type).put("text", it)) }
                    "note" -> ask("Note", "Anything you want on Home", multi = true) { add(JSONObject().put("type", type).put("text", it)) }
                    else -> add(JSONObject().put("type", type))
                }
            }.show()
    }

    private fun buildWidget(w: JSONObject): LinearLayout {
        val c = card()
        fun header(s: String) = c.addView(text(s.uppercase(), 11f, muted, true).apply { letterSpacing = 0.08f; setPadding(0, 0, 0, dp(8)) })
        when (w.optString("type")) {
            "status" -> {
                header("Ollama")
                val dot = View(this).apply { background = round(muted, 6) }
                val line = text("Starting…", 18f, fg, true)
                c.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) }); addView(line)
                })
                c.addView(text(if (builtIn) "Running on this device · tap for settings" else "Remote: $host", 13f, muted).apply { setPadding(0, dp(6), 0, 0) })
                liveUpdaters.add { dot.background = round(statusColor, 6); line.text = statusLine }
                c.setOnClickListener { openSettings() }
            }
            "device" -> {
                header("Device")
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
                c.addView(text("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", 18f, fg, true))
                val stats = text("", 13f, muted).apply { setPadding(0, dp(6), 0, 0) }
                c.addView(stats)
                liveUpdaters.add {
                    val free = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1_000_000_000.0
                    val bat = (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    stats.text = "Chip $soc · ${Runtime.getRuntime().availableProcessors()} cores\n" +
                        "RAM ${"%.1f".format(free)} GB free of ${"%.0f".format(mi.totalMem / 1_000_000_000.0)} GB · Battery $bat%"
                }
            }
            "clock" -> {
                header("Today")
                val time = text("", 34f, fg, true); val date = text("", 14f, muted)
                c.addView(time); c.addView(date)
                liveUpdaters.add {
                    val now = Date()
                    time.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
                    date.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
                }
            }
            "recent" -> {
                header("Recent chats")
                val recent = Store.convs.sortedByDescending { it.updated }.take(4)
                if (recent.isEmpty()) c.addView(text("No chats yet. Swipe to Chat to start one.", 14f, muted))
                recent.forEach { cv ->
                    val proj = Store.project(cv.project)
                    c.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6))
                        addView(text(cv.title, 15f, fg))
                        addView(text((proj?.let { "${it.name} · " } ?: "") + ago(cv.updated), 12f, muted))
                        setOnClickListener { openConv(cv) }
                    })
                }
            }
            "projects" -> {
                header("Projects")
                if (Store.projects.isEmpty()) c.addView(text("No projects yet. Tap to create one.", 14f, muted))
                Store.projects.sortedByDescending { it.updated }.take(4).forEach { p ->
                    c.addView(text("${p.name}  ·  ${Store.projectChats(p.id).size} chats", 15f, fg).apply {
                        setPadding(0, dp(6), 0, dp(6)); setOnClickListener { showProjectDetail(p); pager.go(2) }
                    })
                }
                c.setOnClickListener { pager.go(2) }
            }
            "prompt" -> {
                header("Quick prompt")
                c.addView(text(w.optString("text"), 16f, fg))
                c.addView(text("Tap to ask in Chat", 12f, accent).apply { setPadding(0, dp(8), 0, 0) })
                c.setOnClickListener { pager.go(1); chatPane.input.setText(w.optString("text")); chatPane.onSend() }
            }
            "note" -> {
                header("Note")
                c.addView(text(w.optString("text").ifEmpty { "Tap to write" }, 15f, fg))
                c.setOnClickListener {
                    ask("Note", "Anything you want on Home", w.optString("text"), true) { s ->
                        val l = widgets(); l.firstOrNull { it.toString() == w.toString() }?.put("text", s); saveWidgets(l); refreshHome()
                    }
                }
            }
        }
        return c
    }

    // ===================== Projects =====================
    private fun buildProjects(): View {
        projList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        projDetail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        projPane = ChatPane("")
        projFrame = FrameLayout(this)
        projFrame.addView(ScrollView(this).apply { addView(projList); isFillViewport = true }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        projFrame.addView(ScrollView(this).apply { addView(projDetail); isFillViewport = true }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        projFrame.addView(projPane.root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        showProjectList()
        return projFrame
    }

    private fun showLayer(i: Int) { for (k in 0 until projFrame.childCount) projFrame.getChildAt(k).visibility = if (k == i) View.VISIBLE else View.GONE }

    private fun showProjectList() {
        openProject = null; prefs.edit().remove("curProject").apply()
        projList.removeAllViews()
        projList.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, dp(12))
            addView(text("Projects", 24f, fg, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(chip("+ New project") { newProjectDialog() })
        })
        if (Store.projects.isEmpty()) {
            projList.addView(card().apply {
                addView(text("No projects yet", 17f, fg, true))
                addView(text("A project keeps its own chats, notes and files together, so claw remembers the context. Create one here, or ask in Chat: \"create a project called Garage build\".", 14f, muted).apply { setPadding(0, dp(6), 0, 0) })
            }, cardLp())
        }
        Store.projects.sortedByDescending { it.updated }.forEach { p ->
            projList.addView(card().apply {
                addView(text(p.name, 17f, fg, true))
                val n = Store.projectChats(p.id).size
                addView(text("$n chat${if (n == 1) "" else "s"} · ${ago(p.updated)}" + if (p.notes.isNotBlank()) " · ${p.notes.take(60)}" else "", 13f, muted).apply { setPadding(0, dp(4), 0, 0) })
                setOnClickListener { showProjectDetail(p) }
            }, cardLp())
        }
        showLayer(0)
    }

    private fun newProjectDialog(then: ((Project) -> Unit)? = null) {
        ask("New project", "Project name") { name ->
            if (name.isEmpty()) return@ask
            val p = Store.newProject(name)
            if (then != null) then(p) else showProjectDetail(p)
        }
    }

    private fun showProjectDetail(p: Project) {
        openProject = p; prefs.edit().putString("curProject", p.id).apply()
        projDetail.removeAllViews()
        projDetail.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12))
            addView(FrameLayout(this@MainActivity).apply {
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(Glyph(this@MainActivity, "back", fg), FrameLayout.LayoutParams(dp(16), dp(16)))
                setOnClickListener { showProjectList() }
            })
            addView(text(p.name, 22f, fg, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(chip("Edit") { editProject(p) })
        })
        projDetail.addView(card().apply {
            addView(text("PROJECT NOTES", 11f, muted, true).apply { letterSpacing = 0.08f })
            addView(text(p.notes.ifBlank { "Tap to add goals, context or rules claw should follow in this project." }, 14f, if (p.notes.isBlank()) muted else fg).apply { setPadding(0, dp(6), 0, 0) })
            setOnClickListener { ask("Project notes", "Goals, context, rules", p.notes, true) { p.notes = it; Store.saveProjects(); showProjectDetail(p) } }
        }, cardLp())
        projDetail.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = round(accent, 22); setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(Glyph(this@MainActivity, "plus", t.onAccent), LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(10) })
            addView(text("New chat in ${p.name}", 15f, t.onAccent, true))
            setOnClickListener { openProjectChat(Store.newConv(p.id)) }
        }, cardLp())
        val chats = Store.projectChats(p.id)
        if (chats.isNotEmpty()) projDetail.addView(text("CHATS", 11f, muted, true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(8), 0, dp(8)) })
        chats.forEach { cv ->
            projDetail.addView(card().apply {
                addView(text(cv.title, 16f, fg))
                addView(text(ago(cv.updated), 12f, muted))
                setOnClickListener { openProjectChat(cv) }
                setOnLongClickListener { confirmDeleteChat(cv) { showProjectDetail(p) }; true }
            }, cardLp())
        }
        showLayer(1)
    }

    private fun editProject(p: Project) {
        AlertDialog.Builder(this, dialogStyle).setTitle(p.name)
            .setItems(arrayOf("Rename", "Delete project")) { _, which ->
                if (which == 0) ask("Rename project", "Project name", p.name) { if (it.isNotEmpty()) { p.name = it; Store.saveProjects(); showProjectDetail(p) } }
                else AlertDialog.Builder(this, dialogStyle).setTitle("Delete ${p.name}?")
                    .setMessage("Its chats are deleted too. Files claw made in the project folder stay on the device.")
                    .setPositiveButton("Delete") { _, _ -> Store.deleteProject(p); showProjectList() }
                    .setNegativeButton("Cancel", null).show()
            }.show()
    }

    private fun confirmDeleteChat(cv: Conv, after: () -> Unit) {
        AlertDialog.Builder(this, dialogStyle).setTitle("Delete \"${cv.title}\"?")
            .setPositiveButton("Delete") { _, _ -> Store.deleteConv(cv); after() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun openProjectChat(cv: Conv) {
        val p = Store.project(cv.project) ?: return
        openProject = p
        projPane.open(cv); showLayer(2)
    }

    private fun openConv(cv: Conv) {
        if (cv.project == null) { chatPane.open(cv); pager.go(1) }
        else { Store.project(cv.project)?.let { showProjectDetail(it) }; openProjectChat(cv); pager.go(2) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            pager.index == 2 && projPane.root.visibility == View.VISIBLE -> openProject?.let { showProjectDetail(it) } ?: showProjectList()
            pager.index == 2 && openProject != null -> showProjectList()
            pager.index != 1 -> pager.go(1)
            else -> super.onBackPressed()
        }
    }

    // ===================== Chat pane (used by Chat tab and inside projects) =====================
    class Att(val path: String, val mime: String, val name: String)

    /** [mode] null = regular Chat tab; non-null = project chat (project comes from the open conversation). */
    inner class ChatPane(private val mode: String?) {
        val root = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        lateinit var conv: Conv
        private val title = text("", 17f, fg, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        private val sub = text("", 12f, muted)
        private val subDot = View(this@MainActivity)
        val chat = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(16)) }
        val scroll = ScrollView(this@MainActivity).apply { addView(chat); isFillViewport = true }
        private val attRow = LinearLayout(this@MainActivity).apply { setPadding(dp(10), dp(8), dp(10), 0) }
        private val attScroll = HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(attRow); visibility = View.GONE }
        private val pending = mutableListOf<Att>()
        val input: EditText
        private val sendArrow = Glyph(this@MainActivity, "up", t.onAccent)
        private val stopSquare = View(this@MainActivity).apply { background = round(t.onAccent, 4); visibility = View.GONE }
        private var proc: Process? = null
        private var cancel: (() -> Unit)? = null
        @Volatile private var stopped = false
        private var busy = false

        init {
            // Sub-header: back (projects) · title + status · history / new
            val head = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(2), dp(12), dp(6)) }
            if (mode != null) head.addView(FrameLayout(this@MainActivity).apply {
                setPadding(dp(8), dp(10), dp(12), dp(10))
                addView(Glyph(this@MainActivity, "back", fg), FrameLayout.LayoutParams(dp(16), dp(16)))
                setOnClickListener { hideKeyboard(); openProject?.let { showProjectDetail(it) } ?: showProjectList() }
            })
            statusViews.add(subDot to sub)
            head.addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, 0, 0)
                addView(title)
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(subDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) }); addView(sub)
                    setOnClickListener { openSettings() }
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            headMain = head.getChildAt(head.childCount - 1)
            val searchBtn = FrameLayout(this@MainActivity).apply {
                background = round(surface2, 18)
                addView(Glyph(this@MainActivity, "search", fg), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                setOnClickListener { openSearch() }
            }
            head.addView(searchBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) })
            headExtras += searchBtn
            if (mode == null) head.addView(chip("Chats") { showHistory() }.also { headExtras += it }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
            head.addView(chip("New") { if (!busy) open(Store.newConv(if (mode == null) null else conv.project)) }.also { headExtras += it })
            buildSearchBox(head)

            input = EditText(this@MainActivity).apply {
                hint = if (mode == null) "Message claw" else "Message claw about this project"
                setHintTextColor(muted); setTextColor(fg)
                background = null; textSize = 16f; maxLines = 6; minHeight = dp(44)
                setPadding(dp(16), dp(10), dp(8), dp(10))
            }
            val plusBtn = FrameLayout(this@MainActivity).apply {
                background = round(surface2, 22)
                addView(Glyph(this@MainActivity, "plus", fg), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                setOnClickListener { pickAttachment(this@ChatPane) }
            }
            val sendBtn = FrameLayout(this@MainActivity).apply {
                background = round(accent, 22)
                // Arrow and stop square share the same 18dp box.
                addView(sendArrow, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                addView(stopSquare, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                setOnClickListener { onSend() }
            }
            val bar = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.BOTTOM
                setPadding(0, dp(4), dp(4), dp(4))
                addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(plusBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(6) })
                addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
            }
            // Pill: attachment previews on top, input row below; button sits 4dp from the pill edge.
            val composer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; background = panel(26)
                addView(attScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val wrap = LinearLayout(this@MainActivity).apply {
                setPadding(dp(14), dp(6), dp(14), dp(14))
                addView(composer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            root.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            root.addView(resultsScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            root.addView(wrap, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            sub.text = statusLine; subDot.background = round(statusColor, 4)
        }

        private val project get() = Store.project(conv.project)

        // ---------- Search: icon stretches into a bar. Chat tab = all chats; project chat = this chat only ----------
        private var headMain: View? = null
        private val headExtras = mutableListOf<View>()
        private lateinit var searchBox: LinearLayout
        private lateinit var searchField: EditText
        private val searchCount = text("", 12f, muted)
        private val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(16)) }
        private val resultsScroll = ScrollView(this@MainActivity).apply { addView(results); visibility = View.GONE }
        private val bubbleViews = mutableListOf<TextView>()
        private val saved = HashMap<TextView, CharSequence>()
        private var hits = listOf<Int>(); private var hitIdx = 0
        private var searching = false

        private fun buildSearchBox(head: LinearLayout) {
            fun iconBtn(kind: String, click: () -> Unit) = FrameLayout(this@MainActivity).apply {
                addView(Glyph(this@MainActivity, kind, fg), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)); setOnClickListener { click() }
            }
            searchField = EditText(this@MainActivity).apply {
                hint = if (mode == null) "Search all chats" else "Search this chat"; setHintTextColor(muted); setTextColor(fg)
                background = null; setSingleLine(); textSize = 15f; setPadding(dp(4), dp(6), dp(4), dp(6))
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(e: android.text.Editable?) { runSearch(e.toString().trim()) }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            searchBox = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; background = round(surface2, 18); setPadding(dp(12), 0, dp(4), 0); visibility = View.GONE
                addView(Glyph(this@MainActivity, "search", muted), LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(6) })
                addView(searchField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(searchCount)
                if (mode != null) {
                    addView(iconBtn("chevup") { jump(-1) }, LinearLayout.LayoutParams(dp(32), dp(36)))
                    addView(iconBtn("chevdown") { jump(1) }, LinearLayout.LayoutParams(dp(32), dp(36)))
                }
                addView(iconBtn("close") { closeSearch() }, LinearLayout.LayoutParams(dp(34), dp(36)))
            }
            head.addView(searchBox, LinearLayout.LayoutParams(dp(36), dp(36)))
        }

        private fun openSearch() {
            if (searching) return
            searching = true
            val head = searchBox.parent as LinearLayout
            val full = head.width - head.paddingLeft - head.paddingRight - (head.getChildAt(0).takeIf { mode != null }?.width ?: 0)
            headMain?.visibility = View.GONE; headExtras.forEach { it.visibility = View.GONE }
            searchBox.visibility = View.VISIBLE; searchBox.alpha = 0.4f
            android.animation.ValueAnimator.ofInt(dp(36), full).apply {
                duration = 260; interpolator = android.view.animation.DecelerateInterpolator(1.8f)
                addUpdateListener { searchBox.layoutParams = searchBox.layoutParams.apply { width = it.animatedValue as Int }; searchBox.alpha = 0.4f + 0.6f * it.animatedFraction; searchBox.requestLayout() }
                doOnEndCompat { searchField.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchField, 0) }
                start()
            }
        }

        private fun closeSearch() {
            if (!searching) return
            searching = false
            searchField.setText(""); hideKeyboard(); clearHighlights()
            resultsScroll.visibility = View.GONE; scroll.visibility = View.VISIBLE
            android.animation.ValueAnimator.ofInt(searchBox.width, dp(36)).apply {
                duration = 200; interpolator = android.view.animation.AccelerateInterpolator()
                addUpdateListener { searchBox.layoutParams = searchBox.layoutParams.apply { width = it.animatedValue as Int }; searchBox.alpha = 1f - 0.6f * it.animatedFraction; searchBox.requestLayout() }
                doOnEndCompat { searchBox.visibility = View.GONE; headMain?.visibility = View.VISIBLE; headExtras.forEach { it.visibility = View.VISIBLE } }
                start()
            }
        }

        private fun android.animation.ValueAnimator.doOnEndCompat(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) { f() }
        })

        private fun runSearch(q: String) {
            if (mode != null) { findInChat(q); return }
            results.removeAllViews()
            if (q.isEmpty()) { resultsScroll.visibility = View.GONE; scroll.visibility = View.VISIBLE; searchCount.text = ""; return }
            resultsScroll.visibility = View.VISIBLE; scroll.visibility = View.GONE
            var n = 0
            Store.regularChats().forEach { c ->
                val msgs = Store.msgs(c)
                val inTitle = c.title.contains(q, true)
                val m = msgs.firstOrNull { it.text.contains(q, true) }
                if (!inTitle && m == null) return@forEach
                n++
                val card = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; background = panel(18); setPadding(dp(14), dp(12), dp(14), dp(12)) }
                card.addView(text(c.title, 15f, fg, true).apply { this.text = highlight(c.title, q) })
                if (m != null) {
                    val i = m.text.indexOf(q, ignoreCase = true)
                    val from = maxOf(0, i - 40); val snip = (if (from > 0) "…" else "") + m.text.substring(from, minOf(m.text.length, i + q.length + 80)).replace('\n', ' ')
                    card.addView(text("", 13f, muted).apply { this.text = highlight((if (m.user) "You: " else "claw: ") + snip, q); maxLines = 3 })
                }
                card.addView(text(ago(c.updated), 11f, muted).apply { setPadding(0, dp(4), 0, 0) })
                card.setOnClickListener { if (!busy) { closeSearch(); open(c); scroll.post { findInChat(q, true) } } }
                results.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
            searchCount.text = "$n  "
            if (n == 0) results.addView(text("No chats mention \"$q\".", 14f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(30), 0, 0) })
        }

        private fun highlight(s: String, q: String): CharSequence {
            if (q.isEmpty()) return s
            val sp = android.text.SpannableString(s)
            var i = s.indexOf(q, 0, true)
            while (i >= 0) {
                sp.setSpan(android.text.style.BackgroundColorSpan(withAlpha(accent, 110)), i, i + q.length, 0)
                sp.setSpan(android.text.style.StyleSpan(Typeface.BOLD), i, i + q.length, 0)
                i = s.indexOf(q, i + q.length, true)
            }
            return sp
        }

        private fun clearHighlights() { saved.forEach { (tv, orig) -> tv.text = orig }; saved.clear(); hits = emptyList() }

        /** Highlight every match inside this chat and jump to the newest; [flash] = keep highlights after closing search. */
        private fun findInChat(q: String, flash: Boolean = false) {
            clearHighlights()
            if (q.isEmpty()) { searchCount.text = ""; return }
            hits = bubbleViews.indices.filter { bubbleViews[it].text.toString().contains(q, true) }
            hits.forEach { i -> val tv = bubbleViews[i]; saved[tv] = tv.text; tv.text = highlight(tv.text.toString(), q) }
            hitIdx = hits.size - 1
            searchCount.text = if (hits.isEmpty()) "0  " else "${hitIdx + 1}/${hits.size}  "
            if (hits.isNotEmpty()) scrollToHit()
            if (flash) scroll.postDelayed({ if (!searching) clearHighlights() }, 4000)
        }
        private fun jump(d: Int) {
            if (hits.isEmpty()) return
            hitIdx = (hitIdx + d + hits.size) % hits.size
            searchCount.text = "${hitIdx + 1}/${hits.size}  "; scrollToHit()
        }
        private fun scrollToHit() {
            val tv = bubbleViews[hits[hitIdx]]
            var y = 0; var v: View = tv
            while (v !== chat && v.parent is View) { y += v.top; v = v.parent as View }
            scroll.smoothScrollTo(0, maxOf(0, y - dp(60)))
        }

        fun open(c: Conv) {
            conv = c
            bubbleViews.clear(); saved.clear(); hits = emptyList()
            if (mode == null) prefs.edit().putString("curChat", c.id).apply()
            chat.removeAllViews(); pending.clear(); renderPending()
            title.text = if (mode == null) (if (c.title == "New chat") "Chat" else c.title) else "${project?.name ?: "Project"} · ${c.title}"
            val msgs = Store.msgs(c)
            if (msgs.isEmpty()) {
                bubble(if (mode == null) "Hi. I'm claw, running right on this device with Ollama.\nAsk me anything, attach photos, videos or files with +, or say \"create a project called …\"."
                       else "New chat in ${project?.name}. I'll keep this project's notes in mind, and files I make go in its folder.", false, emptyList())
            } else msgs.forEach { m ->
                val tv = bubble(m.text, m.user, m.atts)
                if (m.stopped) markStopped(tv, m.text)
            }
        }

        private fun showHistory() {
            val list = Store.regularChats()
            if (list.isEmpty()) { AlertDialog.Builder(this@MainActivity, dialogStyle).setTitle("Chats").setMessage("No saved chats yet.").setPositiveButton("OK", null).show(); return }
            AlertDialog.Builder(this@MainActivity, dialogStyle).setTitle("Chats")
                .setItems(list.map { "${it.title}  ·  ${ago(it.updated)}" }.toTypedArray()) { _, i -> if (!busy) open(list[i]) }
                .setNeutralButton("Delete current") { _, _ ->
                    if (!busy && Store.convs.any { it.id == conv.id }) confirmDeleteChat(conv) { open(Store.newConv(null)) }
                }
                .setPositiveButton("Close", null).show()
        }

        fun addPending(a: Att) { pending.add(a); renderPending() }

        private fun renderPending() {
            attRow.removeAllViews()
            attScroll.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
            pending.toList().forEach { a ->
                val tile = FrameLayout(this@MainActivity)
                tile.addView(attThumb(a.path, a.mime, a.name, dp(64)), FrameLayout.LayoutParams(dp(64), dp(64)))
                tile.addView(TextView(this@MainActivity).apply {
                    text = "×"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                    background = round(Color.argb(200, 0, 0, 0), 11)
                    setOnClickListener { pending.remove(a); renderPending() }
                }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { topMargin = dp(3); rightMargin = dp(3) })
                attRow.addView(tile, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(8) })
            }
        }

        private fun bubble(s: String, user: Boolean, atts: List<String>): TextView {
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = if (user) Gravity.END else Gravity.START }
            if (atts.isNotEmpty()) {
                val row = LinearLayout(this@MainActivity).apply { gravity = if (user) Gravity.END else Gravity.START; setPadding(0, 0, 0, dp(6)) }
                atts.forEach { p ->
                    val f = File(p)
                    row.addView(attThumb(p, guessMime(f.name), f.name, dp(110)).apply { setOnClickListener { viewFile(f) } },
                        LinearLayout.LayoutParams(dp(110), dp(110)).apply { leftMargin = dp(6) })
                }
                col.addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            }
            val tv = TextView(this@MainActivity).apply {
                text = s; textSize = 15.5f; setTextIsSelectable(true)
                setLineSpacing(0f, 1.15f)
                setTextColor(bubbleFg(user))
                background = bubbleBg(user)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
                if (s.isEmpty()) visibility = View.GONE
            }
            col.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            bubbleViews += tv
            chat.addView(LinearLayout(this@MainActivity).apply {
                gravity = if (user) Gravity.END else Gravity.START
                setPadding(0, dp(5), 0, dp(5)); addView(col)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            return tv
        }

        private fun markStopped(out: TextView, partial: String) {
            val note = "Not finished · you stopped this reply"
            val full = if (partial.isEmpty()) note else "$partial\n\n$note"
            val span = android.text.SpannableString(full)
            val start = full.length - note.length
            span.setSpan(android.text.style.ForegroundColorSpan(muted), start, full.length, 0)
            span.setSpan(android.text.style.StyleSpan(Typeface.ITALIC), start, full.length, 0)
            span.setSpan(android.text.style.RelativeSizeSpan(0.85f), start, full.length, 0)
            out.text = span; out.visibility = View.VISIBLE
        }

        private fun setBusy(b: Boolean) {
            busy = b
            sendArrow.visibility = if (b) View.GONE else View.VISIBLE
            stopSquare.visibility = if (b) View.VISIBLE else View.GONE
        }

        fun workDir(): File =
            (if (conv.project != null) File(filesDir, "projects/${conv.project}") else File(filesDir, "workspace")).apply { mkdirs() }

        fun kill() { stopped = true; proc?.destroy(); cancel?.invoke() }

        fun onSend() {
            // Stop must work even when the input box is empty.
            if (busy) {
                stopped = true
                val p = proc; val c = cancel
                thread { c?.invoke(); p?.destroy(); Thread.sleep(800); if (p?.isAlive == true) p.destroyForcibly() }
                return
            }
            val msg = input.text.toString().trim()
            val atts = pending.toList()
            if (msg.isEmpty() && atts.isEmpty()) return
            input.setText(""); pending.clear(); renderPending(); hideKeyboard()

            // "create a project called X" from the regular chat.
            if (mode == null && atts.isEmpty()) {
                val m = Regex("(?i)^(?:please\\s+)?(?:can you\\s+)?(?:create|make|start|add|new)\\s+(?:a\\s+|me\\s+a\\s+)?(?:new\\s+)?project(?:\\s+(?:called|named|for))?\\s*[:\\-]?\\s*(.*)$").find(msg)
                if (m != null) {
                    val name = m.groupValues[1].trim().trim('"', '\'', '.', '?', '!')
                    bubble(msg, true, emptyList())
                    if (name.isEmpty()) {
                        bubble("Sure. What should the project be called?", false, emptyList())
                        newProjectDialog { p -> bubble("Created project \"${p.name}\". It's in the Projects tab, swipe left to open it.", false, emptyList()) }
                    } else {
                        val p = Store.newProject(name)
                        bubble("Created project \"${p.name}\". It's in the Projects tab, swipe left to open it and start a chat there.", false, emptyList())
                        showProjectDetail(p)
                    }
                    return
                }
            }

            val msgs = Store.msgs(conv)
            val prior = msgs.toList()
            val um = Msg(true, msg, atts.map { it.path }); msgs.add(um)
            if (conv.title == "New chat") {
                conv.title = (msg.ifEmpty { atts.first().name }).lines().first().take(40)
                title.text = if (mode == null) conv.title else "${project?.name} · ${conv.title}"
            }
            Store.saveConv(conv)
            bubble(msg, true, um.atts)
            val out = bubble("…", false, emptyList())
            val am = Msg(false, ""); msgs.add(am)
            setBusy(true); stopped = false
            val visual = atts.filter { it.mime.startsWith("image/") || it.mime.startsWith("video/") }
            thread {
                val sb = StringBuilder()
                fun show() { val s = sb.toString().trim(); runOnUiThread { out.text = s.ifEmpty { "…" }; scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } } }
                try {
                    if (visual.isNotEmpty()) visionChat(prior, msg, atts, visual, sb, ::show)
                    else clawChat(prior, msg, atts, sb, ::show)
                } catch (ex: Exception) {
                    if (!stopped) { sb.setLength(0); sb.append("Error: ${ex.message}"); show() }
                } finally {
                    proc = null; cancel = null
                    am.text = sb.toString().trim(); am.stopped = stopped
                    Store.saveConv(conv)
                    val partial = am.text
                    runOnUiThread { if (stopped) markStopped(out, partial); setBusy(false) }
                }
            }
        }

        private fun context(prior: List<Msg>): String {
            val h = StringBuilder()
            prior.forEach { m ->
                h.append(if (m.user) "User: " else "Assistant: ").append(m.text)
                if (m.atts.isNotEmpty()) h.append(" [attached: ${m.atts.joinToString { File(it).name }}]")
                if (m.stopped) h.append(" [reply cut off by user]")
                h.append("\n")
            }
            return if (h.length > 8000) h.substring(h.length - 8000) else h.toString()
        }

        private fun projectPreamble(): String {
            val p = project ?: return ""
            return "You are working in the project \"${p.name}\". The current directory is this project's folder." +
                (if (p.notes.isNotBlank()) "\nProject notes:\n${p.notes}" else "") + "\n\n"
        }

        private fun clawChat(prior: List<Msg>, msg: String, atts: List<Att>, sb: StringBuilder, show: () -> Unit) {
            val work = workDir()
            val files = StringBuilder()
            atts.forEach { a ->
                val f = File(a.path)
                val rel = f.relativeToOrNull(work)?.path ?: f.absolutePath
                if (a.mime.startsWith("text/") || a.mime.contains("json") || a.mime.contains("xml") || isTextName(a.name)) {
                    val body = try { f.readText().take(20000) } catch (_: Exception) { "" }
                    files.append("\n\nAttached file $rel:\n```\n$body\n```")
                } else files.append("\n\nAttached file: $rel (${a.mime}, ${f.length() / 1024} KB). Use your tools to inspect it if needed.")
            }
            val ctx = context(prior)
            val body = msg.ifEmpty { "Please look at the attached file(s)." } + files
            val prompt = projectPreamble() + (if (ctx.isBlank()) body else "Earlier in this conversation:\n$ctx\nNew message from the user:\n$body")
            val pb = ProcessBuilder("${applicationInfo.nativeLibraryDir}/libclaw.so", "--model", model,
                "--dangerously-skip-permissions", "prompt", prompt).directory(work).redirectErrorStream(true)
            val e = pb.environment(); env(e)
            e["CLAW_CONFIG_HOME"] = File(filesDir, "home/.claw").absolutePath
            e["NO_COLOR"] = "1"; e["TERM"] = "dumb"; e["OLLAMA_HOST"] = host
            val p = pb.start(); proc = p; p.outputStream.close()
            p.inputStream.bufferedReader().use { r ->
                val buf = CharArray(512)
                while (true) {
                    val n = r.read(buf); if (n < 0) break
                    sb.append(String(buf, 0, n).replace(ansi, "")); show()
                }
            }
            val code = p.waitFor()
            if (!stopped && code != 0 && sb.isBlank()) { sb.append("claw stopped (code $code). Is a model downloaded?"); show() }
        }

        /** Images/videos go straight to Ollama's chat API so a vision model can actually see them. */
        private fun visionChat(prior: List<Msg>, msg: String, atts: List<Att>, visual: List<Att>, sb: StringBuilder, show: () -> Unit) {
            val images = JSONArray()
            var notes = ""
            visual.forEach { a ->
                if (a.mime.startsWith("image/")) loadScaled(a.path, 1024)?.let { images.put(b64(it)) }
                else {
                    val frames = videoFrames(a.path, 4)
                    frames.forEach { images.put(b64(it)) }
                    notes += "\n(The video \"${a.name}\" is shown as ${frames.size} frames in time order.)"
                }
            }
            atts.filter { it !in visual }.forEach { a ->
                val f = File(a.path)
                notes += if (isTextName(a.name) || a.mime.startsWith("text/")) "\n\nAttached file ${a.name}:\n```\n${try { f.readText().take(20000) } catch (_: Exception) { "" }}\n```"
                         else "\n(Also attached: ${a.name}, ${a.mime})"
            }
            val messages = JSONArray()
            val pre = projectPreamble()
            if (pre.isNotEmpty()) messages.put(JSONObject().put("role", "system").put("content", pre.trim()))
            prior.takeLast(20).forEach { m -> if (m.text.isNotBlank()) messages.put(JSONObject().put("role", if (m.user) "user" else "assistant").put("content", m.text)) }
            messages.put(JSONObject().put("role", "user").put("content", msg.ifEmpty { "What's in this?" } + notes).put("images", images))
            val c = URL("$host/api/chat").openConnection() as HttpURLConnection
            cancel = { try { c.disconnect() } catch (_: Exception) {} }
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.connectTimeout = 5000; c.readTimeout = 600000
            c.outputStream.use { it.write(JSONObject().put("model", model).put("stream", true).put("messages", messages).toString().toByteArray()) }
            val stream = if (c.responseCode >= 400) c.errorStream else c.inputStream
            stream.bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val j = try { JSONObject(line) } catch (_: Exception) { return@forEachLine }
                if (j.has("error")) {
                    val err = j.getString("error")
                    sb.append(if (err.contains("image", true) || err.contains("vision", true) || err.contains("multimodal", true))
                        "$model can't see images. Pick a vision model in Settings, like gemma3:4b, qwen2.5vl:3b or llava-phi3, then try again.\n($err)"
                        else "Ollama error: $err")
                } else sb.append(j.optJSONObject("message")?.optString("content") ?: "")
                show()
            }
        }
    }

    // ===================== Attachments =====================
    private fun pickAttachment(pane: ChatPane) {
        pickingPane = pane
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "text/*", "application/*", "*/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 77)
    }

    private fun importAttachment(pane: ChatPane, uri: Uri) {
        thread {
            try {
                var name = "file"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) name = cur.getString(0) ?: name
                }
                val mime = contentResolver.getType(uri) ?: guessMime(name)
                val dir = File(pane.workDir(), "attachments").apply { mkdirs() }
                var dest = File(dir, name.replace('/', '_'))
                var k = 1
                while (dest.exists()) { dest = File(dir, "${name.substringBeforeLast('.')}-$k" + (if (name.contains('.')) ".${name.substringAfterLast('.')}" else "")); k++ }
                contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { i.copyTo(it) } }
                runOnUiThread { pane.addPending(Att(dest.absolutePath, mime, dest.name)) }
            } catch (e: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this, "Couldn't attach: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun isTextName(n: String) = n.substringAfterLast('.', "").lowercase() in
        setOf("txt", "md", "json", "csv", "kt", "java", "rs", "py", "js", "ts", "html", "css", "xml", "yml", "yaml", "toml", "sh", "c", "cpp", "h", "go", "log", "ini", "cfg")

    private fun guessMime(n: String): String {
        val ext = n.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: if (isTextName(n)) "text/plain" else "application/octet-stream"
    }

    private fun loadScaled(path: String, max: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= max || o.outHeight / (s * 2) >= max) s *= 2
        val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        if (b == null) null else {
            val k = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
            if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
        }
    } catch (_: Exception) { null }

    private fun videoFrames(path: String, n: Int): List<Bitmap> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (1..n).mapNotNull { k ->
                r.getFrameAtTime(dur * 1000 * k / (n + 1), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                    val s = minOf(1f, 768f / maxOf(b.width, b.height))
                    if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
                }
            }
        } catch (_: Exception) { emptyList() } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun b64(b: Bitmap): String {
        val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 85, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    /** Square preview: photo/video frame, or a file tile with its extension and name. */
    private fun attThumb(path: String, mime: String, name: String, size: Int): View {
        val frame = FrameLayout(this).apply { background = round(surface2, 14); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            thread {
                val b = if (mime.startsWith("image/")) loadScaled(path, size * 2) else videoFrames(path, 1).firstOrNull()
                runOnUiThread { img.setImageBitmap(b) }
            }
            if (mime.startsWith("video/")) frame.addView(TextView(this).apply {
                text = "▶"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = round(Color.argb(170, 0, 0, 0), 12)
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        } else {
            frame.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6))
                addView(text(name.substringAfterLast('.', "file").uppercase().take(5), 14f, accent, true).apply { gravity = Gravity.CENTER })
                addView(text(name, 10f, muted).apply { maxLines = 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        return frame
    }

    private fun viewFile(f: File) {
        if (guessMime(f.name).startsWith("image/")) {
            val iv = ImageView(this).apply { adjustViewBounds = true; setImageBitmap(loadScaled(f.absolutePath, 1600)) }
            AlertDialog.Builder(this, dialogStyle).setView(iv).setPositiveButton("Close", null).show()
        } else android.widget.Toast.makeText(this, "${f.name} · ${f.length() / 1024} KB", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ===================== Built-in Ollama =====================
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
                // Performance: faster attention, smaller KV cache, keep the model warm between messages.
                pb.environment()["OLLAMA_FLASH_ATTENTION"] = "1"
                pb.environment()["OLLAMA_KV_CACHE_TYPE"] = "q8_0"
                pb.environment()["OLLAMA_KEEP_ALIVE"] = "30m"
                pb.environment()["OLLAMA_NUM_PARALLEL"] = "1"
                pb.environment()["OLLAMA_MAX_LOADED_MODELS"] = "1"
                ollama = pb.start()
            } catch (e: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this, "Couldn't start built-in Ollama: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
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
            runOnUiThread {
                statusColor = if (hasModel) good else if (ok) amber else muted
                statusLine = when {
                    hasModel && builtIn -> "Ready · $model on this device"
                    hasModel -> "Ready · $model via Ollama"
                    ok -> "Ollama running · $model not downloaded"
                    builtIn -> "Starting Ollama…"
                    else -> "Can't reach Ollama"
                }
                statusViews.forEach { (d, tv) -> d.background = round(statusColor, 5); tv.text = statusLine }
                liveUpdaters.forEach { it() }
            }
            Thread.sleep(3000)
        }
    }

    // ===================== Settings =====================
    private var settingsDialog: AlertDialog? = null
    private fun openSettings() {
        val pad = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, dp(8)); setBackgroundColor(t.surface) }
        fun label(s: String) = TextView(this).apply { text = s; setTextColor(muted); textSize = 12f; setPadding(0, dp(14), 0, dp(6)) }
        fun field(v: String, h: String) = EditText(this).apply {
            setText(v); hint = h; setTextColor(fg); setHintTextColor(muted); setSingleLine()
            background = round(surface2, 12); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val sw = Switch(this).apply { text = "Run Ollama on this device"; setTextColor(fg); isChecked = builtIn }
        val hostF = field(prefs.getString("host", "")!!, "http://192.168.1.50:11434").apply { inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val modelF = field(model, "llama3.2:1b")
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        val pullStatus = TextView(this).apply { setTextColor(muted); textSize = 12f }
        val models = TextView(this).apply { setTextColor(fg); textSize = 13f }
        val pull = Button(this).apply {
            text = "Download model"; setTextColor(t.onAccent); background = round(accent, 12); isAllCaps = false
        }
        box.addView(sw)
        box.addView(label("Remote Ollama host (when not on this device)")); box.addView(hostF)
        box.addView(label("Model")); box.addView(modelF)
        val ramGb = (android.app.ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }.totalMem / 1_000_000_000.0)
        val tip = (if (ramGb >= 12) "This device has ${"%.0f".format(ramGb)} GB RAM: it can run 7–8B models like qwen2.5-coder:7b or llama3.1:8b. Small ones (llama3.2:3b) are faster."
                  else "This device has ${"%.0f".format(ramGb)} GB RAM: best with 1–3B models like llama3.2:3b, qwen2.5-coder:1.5b, gemma3:1b.") +
                  "\nTo show it photos or videos, use a vision model like gemma3:4b or qwen2.5vl:3b."
        box.addView(TextView(this).apply { text = tip; setTextColor(muted); textSize = 12f; setPadding(0, dp(6), 0, dp(10)) })
        box.addView(pull, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        box.addView(Button(this).apply {
            text = "Browse & download any model"; setTextColor(fg); background = round(surface2, 12); isAllCaps = false
            setOnClickListener { settingsDialog?.dismiss(); openModels() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        box.addView(progress); box.addView(pullStatus)
        box.addView(label("Installed models")); box.addView(models)
        fun save() {
            prefs.edit().putBoolean("builtin", sw.isChecked).putString("host", hostF.text.toString().trim())
                .putString("model", modelF.text.toString().trim()).apply()
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
        settingsDialog = AlertDialog.Builder(this, dialogStyle)
            .setTitle("Settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done") { _, _ -> save() }
            .show()
    }


    // ===================== Model browser =====================
    private fun ramBytes(): Long = android.app.ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }.totalMem
    private fun fit(bytes: Long): Pair<String, Int> {
        if (bytes <= 0) return "" to muted
        val r = bytes * 1.2 / ramBytes()
        return when { r < 0.6 -> "Fits" to Color.rgb(80, 200, 120); r < 0.85 -> "Tight fit" to Color.rgb(240, 180, 60); else -> "Too big" to Color.rgb(235, 90, 80) }
    }
    private fun norm(n: String) = n.lowercase().let { if (it.substringAfterLast('/').contains(':')) it else "$it:latest" }

    /** Browse every free model (Ollama library + all GGUF models on Hugging Face), newest first, with search, picks and a multi-select download queue. */
    private fun openModels() {
        val dlg = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        var source = prefs.getString("modelSource", "all")!!; var newest = prefs.getBoolean("modelNewest", true)
        var query = ""; var page = 0; var gen = 0
        val selected = LinkedHashMap<String, Long>()
        var installed = setOf<String>()
        val newSet = prefs.getStringSet("newModels", emptySet())!!.toSet()
        prefs.edit().putStringSet("newModels", emptySet()).apply()

        val rootV = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(t.bg); setPadding(dp(16), dp(14), dp(16), dp(10)) }
        fun tv(s: String, size: Float, c: Int = fg, bold: Boolean = false) = TextView(this).apply { text = s; textSize = size; setTextColor(c); if (bold) typeface = Typeface.DEFAULT_BOLD }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("‹", 26f).apply { setPadding(dp(4), 0, dp(14), 0); setOnClickListener { dlg.dismiss() } })
        head.addView(tv("Models", 22f, fg, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(tv("RAM ${"%.0f".format(ramBytes() / 1e9)} GB", 12f, muted))
        rootV.addView(head)
        val search = EditText(this).apply {
            hint = "Search every model: qwen, llama, coder, vision…"; setTextColor(fg); setHintTextColor(muted); setSingleLine()
            background = round(surface2, 22); setPadding(dp(16), dp(11), dp(16), dp(11)); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        rootV.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        val filters = LinearLayout(this)
        rootV.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(filters); setPadding(0, dp(8), 0, dp(4)) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        rootV.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val dlBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val picks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val statusV = tv("", 13f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)) }
        val more = tv("Load more", 14f, accent, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(20)); visibility = View.GONE }
        list.addView(dlBox); list.addView(picks); list.addView(results); list.addView(statusV); list.addView(more)
        val go = Button(this).apply { setTextColor(t.onAccent); background = round(accent, 22); isAllCaps = false; visibility = View.GONE }
        rootV.addView(go, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        fun updateGo() {
            go.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            go.text = "Download ${selected.size} model${if (selected.size == 1) "" else "s"} · ${Catalog.gb(selected.values.sum())}"
        }
        fun section(s: String) = tv(s.uppercase(), 11f, muted, true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(14), 0, dp(6)) }
        fun badge(s: String, c: Int) = tv(s, 11f, c, true).apply { background = GradientDrawable().apply { setStroke(dp(1), c); cornerRadius = dp(8).toFloat() }; setPadding(dp(6), dp(1), dp(6), dp(1)) }

        /** One downloadable row: name, size, fit and a select toggle (or Use, when already installed). */
        fun variantRow(pull: String, label: String, bytes: Long, note: String): View {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(9), dp(10), dp(9)) }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(label, 14f, fg, true))
            val meta = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            if (bytes > 0) meta.addView(tv(Catalog.gb(bytes) + "  ", 12f, muted))
            fit(bytes).let { (s, c) -> if (s.isNotEmpty()) meta.addView(badge(s, c)) }
            if (note.isNotEmpty()) meta.addView(tv("  $note", 12f, muted))
            col.addView(meta)
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val isInstalled = norm(pull) in installed
            val btn = tv("", 13f, fg, true).apply { setPadding(dp(14), dp(7), dp(14), dp(7)) }
            fun paint() {
                when {
                    isInstalled -> { btn.text = if (norm(model) == norm(pull)) "In use" else "Use"; btn.setTextColor(accent); btn.background = round(surface2, 16) }
                    pull in selected -> { btn.text = "✓ Selected"; btn.setTextColor(t.onAccent); btn.background = round(accent, 16) }
                    else -> { btn.text = "+ Select"; btn.setTextColor(fg); btn.background = round(surface2, 16) }
                }
            }
            paint()
            btn.setOnClickListener {
                if (isInstalled) { prefs.edit().putString("model", pull).apply(); pollStatus(); android.widget.Toast.makeText(this, "Now using $pull", android.widget.Toast.LENGTH_SHORT).show(); paint(); return@setOnClickListener }
                if (pull in selected) selected.remove(pull) else selected[pull] = bytes
                paint(); updateGo()
            }
            row.addView(btn)
            return row
        }

        fun entryCard(e: CatalogEntry): View {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(t.surface, 18); setPadding(dp(14), dp(12), dp(14), dp(12)) }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(tv(e.title, 16f, fg, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if ("${e.source}:${e.id}" in newSet) top.addView(badge("NEW", accent))
            top.addView(tv("  " + if (e.source == "hf") "Hugging Face" else "Ollama", 11f, muted))
            c.addView(top)
            if (e.desc.isNotBlank()) c.addView(tv(e.desc, 13f, fg).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
            val info = listOf(Catalog.age(e.date), e.caps.joinToString(" · "), e.stat).filter { it.isNotBlank() }.joinToString("  ·  ")
            c.addView(tv(info, 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
            val vbox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            c.addView(vbox)
            var loaded = false
            c.setOnClickListener {
                vbox.visibility = if (vbox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (loaded) return@setOnClickListener
                loaded = true
                vbox.addView(tv("Loading sizes…", 12f, muted).apply { setPadding(0, dp(8), 0, 0) })
                thread {
                    val vs = try { Catalog.variants(e) } catch (ex: Exception) { null }
                    runOnUiThread {
                        vbox.removeAllViews()
                        when {
                            vs == null -> { loaded = false; vbox.addView(tv("Couldn't load. Tap to retry.", 12f, muted)) }
                            vs.isEmpty() -> vbox.addView(tv("No downloadable builds (cloud-only or not GGUF).", 12f, muted))
                            else -> vs.forEach { v -> vbox.addView(variantRow(v.pull, v.label, v.bytes, v.note)) }
                        }
                    }
                }
            }
            return c.also { it.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) } }
        }

        fun renderPicks() {
            picks.removeAllViews()
            if (query.isNotEmpty()) return
            picks.addView(section("Suggested for this device"))
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(t.surface, 18); setPadding(dp(4), dp(4), dp(4), dp(4)) }
            val ramGb = ramBytes() / 1e9
            Catalog.suggestions.filter { it.minRamGb <= ramGb + 0.5 && it.bytes * 1.2 < ramBytes() * 0.85 }.forEach { s ->
                box.addView(variantRow(s.pull, s.pull, s.bytes, s.why))
            }
            picks.addView(box)
        }

        val dlListener: () -> Unit = {
            dlBox.removeAllViews()
            val jobs = synchronized(Downloads) { Downloads.jobs.toList() }
            if (jobs.isNotEmpty()) {
                val hdr = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                hdr.addView(section("Downloads · keep going with the screen off"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (jobs.any { it.state in setOf("done", "failed", "cancelled") }) hdr.addView(tv("Clear", 12f, accent, true).apply { setPadding(dp(8), dp(10), dp(4), 0); setOnClickListener { Downloads.clearFinished() } })
                dlBox.addView(hdr)
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(t.surface, 18); setPadding(dp(14), dp(8), dp(14), dp(8)) }
                jobs.forEach { j ->
                    val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(tv(j.pull, 14f, fg, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                    col.addView(tv(if (j.total > 0 && j.state == "running") "${Catalog.gb(j.done)} of ${Catalog.gb(j.total)} · ${j.status}" else j.status, 12f, muted))
                    if (j.state == "running") col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100; isIndeterminate = j.total <= 0; if (j.total > 0) progress = (j.done * 100 / j.total).toInt() })
                    r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    if (j.state in setOf("queued", "running")) r.addView(tv("✕", 16f, muted).apply { setPadding(dp(12), dp(4), dp(4), dp(4)); setOnClickListener { Downloads.cancel(j.pull) } })
                    box.addView(r)
                }
                dlBox.addView(box)
            }
        }

        fun refreshInstalled(then: () -> Unit = {}) = thread {
            val set = try {
                val a = JSONObject(URL("$host/api/tags").readText()).getJSONArray("models")
                (0 until a.length()).map { norm(a.getJSONObject(it).getString("name")) }.toSet()
            } catch (_: Exception) { emptySet() }
            runOnUiThread { installed = set; then() }
        }

        fun load(reset: Boolean) {
            val g = ++gen
            if (reset) { page = 0; results.removeAllViews(); renderPicks() } else page++
            more.visibility = View.GONE; statusV.text = "Loading…"; statusV.visibility = View.VISIBLE
            val q = query; val src = source; val nw = newest; val p = page
            thread {
                val got = mutableListOf<CatalogEntry>(); var hasMore = false; var err: String? = null
                if (src != "hf") try { val (l, m) = Catalog.ollama(q, p, nw); got += l; hasMore = hasMore || m } catch (e: Exception) { err = e.message }
                if (src != "ollama") try { val (l, m) = Catalog.hf(q, p, nw); got += l; hasMore = hasMore || m } catch (e: Exception) { err = e.message }
                val sorted = if (nw) got.sortedByDescending { it.date } else got.sortedByDescending { it.downloads }
                runOnUiThread {
                    if (g != gen) return@runOnUiThread
                    if (reset && sorted.isNotEmpty()) results.addView(section(if (q.isEmpty()) (if (nw) "Newest models" else "Most popular") else "Results for \"$q\""))
                    sorted.forEach { results.addView(entryCard(it)) }
                    statusV.visibility = if (sorted.isEmpty() && results.childCount == 0) View.VISIBLE else View.GONE
                    statusV.text = if (err != null && sorted.isEmpty()) "Couldn't reach the model catalogs: $err" else "Nothing found. Try another word."
                    more.visibility = if (hasMore) View.VISIBLE else View.GONE
                }
            }
        }
        more.setOnClickListener { load(false) }

        fun renderFilters() {
            filters.removeAllViews()
            fun f(label: String, on: Boolean, click: () -> Unit) = filters.addView(chip(label, click).apply {
                if (on) { background = round(accent, 16); setTextColor(t.onAccent) }
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) }
            })
            f("Newest", newest) { newest = true; prefs.edit().putBoolean("modelNewest", true).apply(); renderFilters(); load(true) }
            f("Popular", !newest) { newest = false; prefs.edit().putBoolean("modelNewest", false).apply(); renderFilters(); load(true) }
            filters.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
            listOf("all" to "All sources", "ollama" to "Ollama", "hf" to "Hugging Face").forEach { (k, l) ->
                f(l, source == k) { source = k; prefs.edit().putString("modelSource", k).apply(); renderFilters(); load(true) }
            }
            val watch = prefs.getBoolean("watchModels", true)
            f(if (watch) "🔔 New-model alerts on" else "🔕 New-model alerts off", false) {
                prefs.edit().putBoolean("watchModels", !watch).apply(); renderFilters()
            }
        }

        val debounce = android.os.Handler(mainLooper)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                debounce.removeCallbacksAndMessages(null)
                debounce.postDelayed({ val q = e.toString().trim(); if (q != query) { query = q; load(true) } }, 450)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        go.setOnClickListener {
            if (builtIn) startOllama()
            Downloads.enqueue(this, host, selected.keys.toList())
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
            selected.clear(); updateGo(); scroll.smoothScrollTo(0, 0)
        }
        Downloads.listen(dlListener)
        Downloads.onFinished = { refreshInstalled() }
        dlg.setOnDismissListener { Downloads.unlisten(dlListener) }
        renderFilters(); dlListener()
        refreshInstalled { load(true) }
        dlg.setContentView(rootV)
        dlg.show()
        // First open: remember what exists so later releases show up as NEW.
        thread { try { if (prefs.getStringSet("seenModels", emptySet())!!.isEmpty()) Catalog.checkNew(this) } catch (_: Exception) {} }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "models") openModels()
    }

    // ===================== Themes & wallpapers =====================
    /** Animated wallpapers: name -> (kind, background, colors). "Theme flow" follows the current theme (incl. Material You). */
    private fun animatedWalls(): List<Pair<String, Triple<String, Int, IntArray>>> = listOf(
        "Theme flow" to Triple("blobs", t.bg, intArrayOf(t.accent, t.surface2, Color.rgb(Color.blue(t.accent), Color.red(t.accent), Color.green(t.accent)))),
        "Lava" to Triple("blobs", Color.rgb(25, 8, 20), intArrayOf(Color.rgb(255, 90, 40), Color.rgb(230, 40, 120), Color.rgb(140, 40, 200), Color.rgb(255, 170, 60))),
        "Aurora flow" to Triple("blobs", Color.rgb(6, 12, 30), intArrayOf(Color.rgb(30, 220, 160), Color.rgb(40, 120, 255), Color.rgb(160, 80, 255))),
        "Starfield" to Triple("stars", Color.rgb(4, 6, 16), intArrayOf(Color.WHITE, Color.rgb(90, 70, 200))),
        "Waves" to Triple("waves", Color.rgb(8, 16, 36), intArrayOf(Color.rgb(40, 110, 220), Color.rgb(30, 170, 200), Color.rgb(90, 80, 220), Color.rgb(20, 200, 170))),
    )

    private fun applyWallpaper(root: FrameLayout) {
        val w = prefs.getString("wall", "None")
        val grad = wallpapers.firstOrNull { it.first == w }?.second
        // Liquid glass samples a low-res copy of the wallpaper; static wallpapers paint it on layout.
        Backdrop.reset()
        var staticPaint: ((android.graphics.Canvas, Int, Int) -> Unit)? = { c, _, _ -> c.drawColor(t.bg) }
        if (liquidOn) {
            Backdrop.active = true; Backdrop.root = root
            Backdrop.dimColor = t.bg; Backdrop.dimAlpha = if (w != "None") prefs.getInt("dim", 45) / 100f else 0f
            root.viewTreeObserver.addOnScrollChangedListener { Backdrop.changed() }
            root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> staticPaint?.let { p -> Backdrop.render(v.width, v.height) { p(it, v.width, v.height) } } }
        }
        if (grad != null) {
            root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, grad)
            staticPaint = { c, cw, ch -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, grad).apply { setBounds(0, 0, cw, ch); draw(c) } }
        } else if (animatedWalls().any { it.first == w }) {
            staticPaint = null
            val (kind, wbg, cols) = animatedWalls().first { it.first == w }.second
            root.addView(AnimatedWall(this, kind, wbg, cols), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else if (w == "Your video") {
            staticPaint = null
            try { root.addView(VideoWall(this, Uri.parse(prefs.getString("wallVideo", ""))), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) } catch (_: Exception) {}
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
                if (bmp != null) staticPaint = { c, cw, ch ->
                    val sc = maxOf(cw.toFloat() / bmp.width, ch.toFloat() / bmp.height)
                    val dw = bmp.width * sc; val dh = bmp.height * sc
                    c.drawBitmap(bmp, null, android.graphics.RectF((cw - dw) / 2, (ch - dh) / 2, (cw + dw) / 2, (ch + dh) / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                }
            } catch (_: Exception) { }
        }
        val blur = prefs.getInt("blur", 0)
        if (Build.VERSION.SDK_INT >= 31 && blur > 0) for (k in 0 until root.childCount)
            root.getChildAt(k).setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(blur).toFloat(), dp(blur).toFloat(), android.graphics.Shader.TileMode.CLAMP))
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
            return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        }
        var dialog: AlertDialog? = null
        box.addView(label("Theme"))
        box.addView(swatchRow(allThemes().map { th ->
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
        box.addView(label("Animated wallpaper"))
        box.addView(swatchRow(animatedWalls().map { (name, v) ->
            name to GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(v.third[0], v.second, v.third[v.third.size - 1]))
        } + ("Your video" to GradientDrawable().apply { setColor(t.surface2) }), prefs.getString("wall", "None")!!) { name ->
            dialog?.dismiss()
            if (name == "Your video") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "video/*" }, 43)
            } else { prefs.edit().putString("wall", name).apply(); recreate() }
        })
        fun slider(name: String, key: String, def: Int, max: Int) {
            box.addView(label(name))
            box.addView(android.widget.SeekBar(this).apply {
                this.max = max; progress = prefs.getInt(key, def)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, user: Boolean) {}
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) { prefs.edit().putInt(key, sb.progress).apply(); dialog?.dismiss(); recreate() }
                })
            })
        }
        slider("Chat bubble opacity", "bubbleOpacity", 100, 100)
        box.addView(label("Liquid glass"))
        box.addView(Switch(this).apply {
            text = "Glass bubbles, cards and bars"; setTextColor(fg); isChecked = glassOn
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("glass", on).apply(); dialog?.dismiss(); recreate() }
        })
        box.addView(label("Glass style"))
        box.addView(swatchRow(listOf(
            "Liquid" to GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(withAlpha(Color.WHITE, 200), withAlpha(accent, 60), withAlpha(Color.WHITE, 140))),
            "Frosted" to GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(Color.WHITE, 110), withAlpha(Color.WHITE, 40)))
        ), prefs.getString("glassStyle", "Liquid")!!) { name ->
            dialog?.dismiss(); prefs.edit().putString("glassStyle", name).putBoolean("glass", true).apply(); recreate()
        })
        if (!LiquidGlass.supported) box.addView(TextView(this).apply { text = "Liquid refraction needs Android 13+; Frosted is used here."; setTextColor(muted); textSize = 11f })
        box.addView(label("Glass tint"))
        box.addView(swatchRow(glassTints.map { (name, c) ->
            name to GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(withAlpha(c, 230), withAlpha(c, 90)))
        }, prefs.getString("glassTint", "Clear")!!) { name ->
            dialog?.dismiss()
            if (name == "Custom") pickGlassColor()
            else { prefs.edit().putString("glassTint", name).putBoolean("glass", true).apply(); recreate() }
        })
        slider("Glass tint strength", "glassStrength", 30, 100)
        if (Build.VERSION.SDK_INT >= 31) slider("Wallpaper blur (frosted glass)", "blur", 0, 25)
        box.addView(label("Wallpaper dimming"))
        box.addView(android.widget.SeekBar(this).apply {
            max = 90; progress = prefs.getInt("dim", 45)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, user: Boolean) {}
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) { prefs.edit().putInt("dim", sb.progress).apply(); dialog?.dismiss(); recreate() }
            })
        })
        dialog = AlertDialog.Builder(this, dialogStyle).setTitle("Look").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null).show()
    }

    /** Any-color glass tint: hue, saturation and brightness sliders with a live glass preview. */
    private fun pickGlassColor() {
        val hsv = FloatArray(3); Color.colorToHSV(prefs.getInt("glassCustom", Color.rgb(120, 200, 255)), hsv)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        val preview = View(this)
        val previewWrap = FrameLayout(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.accent, t.bg)).apply { cornerRadius = dp(18).toFloat() }; setPadding(dp(18), dp(18), dp(18), dp(18)); addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, dp(56))) }
        fun color() = Color.HSVToColor(hsv)
        fun update() { preview.background = glass(18, color()) }
        box.addView(previewWrap)
        listOf(Triple("Hue", 0, 360), Triple("Saturation", 1, 100), Triple("Brightness", 2, 100)).forEach { (name, i, max) ->
            box.addView(TextView(this).apply { text = name; setTextColor(muted); textSize = 12f; setPadding(0, dp(14), 0, dp(4)) })
            box.addView(android.widget.SeekBar(this).apply {
                this.max = max; progress = if (i == 0) hsv[0].toInt() else (hsv[i] * 100).toInt()
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, user: Boolean) { hsv[i] = if (i == 0) v.toFloat() else v / 100f; update() }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
                })
            })
        }
        update()
        AlertDialog.Builder(this, dialogStyle).setTitle("Glass color").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Use") { _, _ -> prefs.edit().putInt("glassCustom", color()).putString("glassTint", "Custom").putBoolean("glass", true).apply(); recreate() }
            .setNegativeButton("Cancel", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == 42) {
            val uri = data.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.edit().putString("wall", "Your photo").putString("wallUri", uri.toString()).apply()
            recreate()
        } else if (requestCode == 43) {
            val uri = data.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.edit().putString("wall", "Your video").putString("wallVideo", uri.toString()).apply()
            recreate()
        } else if (requestCode == 77) {
            val pane = pickingPane ?: return
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) importAttachment(pane, clip.getItemAt(i).uri)
            else data.data?.let { importAttachment(pane, it) }
        }
    }

    override fun onDestroy() {
        alive = false
        if (::chatPane.isInitialized) chatPane.kill()
        if (::projPane.isInitialized) projPane.kill()
        if (isFinishing) { ollama?.destroy(); ollama = null; stopService(Intent(this, ClawService::class.java)) }
        super.onDestroy()
    }
}
