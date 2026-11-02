package com.buggygamesyt.theclaww

/**
 * Picks the right model for what the user is asking: figures out the kind of task, then
 *  - suggests a better model to download when one fits this device and beats what's installed, and
 *  - (when "Auto-switch" is on) uses the best installed model that's good for the task.
 */
object Advisor {
    class E(val name: String, val gb: Double, val ram: Double)
    class Advice(val task: String, val switchTo: String?, val suggest: E?)

    val LABEL = mapOf("code" to "coding", "vision" to "photos and video", "reason" to "math and reasoning", "write" to "writing", "chat" to "general chat")

    private val CAT: Map<String, List<E>> = mapOf(
        "code" to listOf(E("qwen2.5-coder:32b", 20.0, 24.0), E("qwen2.5-coder:14b", 9.0, 12.0), E("qwen2.5-coder:7b", 4.7, 6.5), E("qwen2.5-coder:3b", 1.9, 3.5), E("qwen2.5-coder:1.5b", 1.0, 2.2), E("qwen2.5-coder:0.5b", 0.4, 1.2)),
        "vision" to listOf(E("qwen2.5vl:32b", 21.0, 26.0), E("gemma3:27b", 17.0, 22.0), E("gemma3:12b", 8.1, 11.0), E("qwen2.5vl:7b", 6.0, 8.0), E("gemma3:4b", 3.3, 5.0), E("qwen2.5vl:3b", 3.2, 4.5), E("moondream", 1.7, 2.5)),
        "reason" to listOf(E("qwen3:32b", 20.0, 24.0), E("deepseek-r1:14b", 9.0, 12.0), E("qwen3:14b", 9.3, 12.0), E("qwen3:8b", 5.2, 7.0), E("deepseek-r1:8b", 5.2, 7.0), E("qwen3:4b", 2.6, 4.0), E("qwen3:1.7b", 1.4, 2.2), E("deepseek-r1:1.5b", 1.1, 2.0)),
        "write" to listOf(E("gemma3:27b", 17.0, 22.0), E("gemma3:12b", 8.1, 11.0), E("llama3.1:8b", 4.9, 7.0), E("gemma3:4b", 3.3, 5.0), E("llama3.2:3b", 2.0, 3.5), E("gemma3:1b", 0.8, 1.5)),
        "chat" to listOf(E("qwen3:14b", 9.3, 12.0), E("gemma3:12b", 8.1, 11.0), E("qwen3:8b", 5.2, 7.0), E("llama3.1:8b", 4.9, 7.0), E("gemma3:4b", 3.3, 5.0), E("qwen3:4b", 2.6, 4.0), E("llama3.2:3b", 2.0, 3.5), E("llama3.2:1b", 1.3, 2.0), E("gemma3:1b", 0.8, 1.5))
    )

    private val visionFamilies = Regex("^(gemma3:(?!1b)|qwen2\\.5vl|qwen3-vl|llava|bakllava|moondream|llama3\\.2-vision|minicpm-v|granite3\\.2-vision|mistral-small3\\.[12])")

    fun norm(n: String): String { val l = n.trim().lowercase(); return if (l.substringAfterLast('/').contains(':')) l else "$l:latest" }

    fun task(msg: String, hasVisual: Boolean, attNames: List<String>): String {
        if (hasVisual) return "vision"
        val m = msg.lowercase()
        val codeExt = Regex("\\.(kt|java|rs|py|js|ts|c|cpp|h|go|sh|html|css|sql|json|xml|yml|yaml|toml|ps1|bat)$")
        if (attNames.any { codeExt.containsMatchIn(it.lowercase()) } || m.contains("```") ||
            Regex("\\b(code|coding|function|bug|debug|stack ?trace|compile|compiler|python|kotlin|java|rust|javascript|typescript|c\\+\\+|golang|sql|regex|api|script|bash|shell|html|css|refactor|unit test|git|android studio|gradle|npm|cargo|class|method|variable|exception|segfault|syntax)\\b").containsMatchIn(m)) return "code"
        if (Regex("\\b(math|solve|calculate|compute|prove|proof|equation|algebra|calculus|integral|derivative|probability|statistics|logic|puzzle|riddle|step by step|reason|how many|percent)\\b").containsMatchIn(m) ||
            Regex("\\d+\\s*[-+*/^×÷xX]\\s*\\d+").containsMatchIn(m) ||
            Regex("\\d\\s+(times|multiplied by|divided by|plus|minus|over|mod|to the power of|squared|cubed)\\b").containsMatchIn(m) ||
            Regex("\\d\\s*=\\s*\\??\\s*$").containsMatchIn(m)) return "reason"
        if (Regex("\\b(write|rewrite|essay|story|poem|lyrics|email|letter|cover letter|blog|article|summari[sz]e|summary|translate|paraphrase|proofread|caption|speech)\\b").containsMatchIn(m)) return "write"
        return "chat"
    }

    /** [ramGb] total RAM; [share] how much of it a model may use (phones/tablets need headroom). */
    fun advise(task: String, installed: List<String>, current: String, ramGb: Double, share: Double): Advice {
        val fits = (CAT[task] ?: CAT.getValue("chat")).filter { it.ram <= ramGb * share }
        val inst = installed.map { norm(it) }.toSet()
        fun rank(n: String) = fits.indexOfFirst { norm(it.name) == norm(n) }.let { if (it < 0) Int.MAX_VALUE else it }
        var cur = rank(current)
        // Vision tasks need a vision model; anything vision-capable beats a text-only model.
        val curVisionOk = task != "vision" || visionFamilies.containsMatchIn(norm(current))
        if (task == "vision" && curVisionOk && cur == Int.MAX_VALUE) cur = fits.size
        val bestInst = fits.firstOrNull { norm(it.name) in inst }
        val instRank = bestInst?.let { rank(it.name) } ?: Int.MAX_VALUE
        val switchTo = if (bestInst != null && norm(bestInst.name) != norm(current) && (instRank < cur || !curVisionOk)) bestInst.name
            else if (task == "vision" && !curVisionOk) installed.firstOrNull { visionFamilies.containsMatchIn(norm(it)) } else null
        val have = minOf(cur, instRank)
        val best = fits.firstOrNull()
        val suggest = if (best != null && norm(best.name) !in inst && rank(best.name) < have) best else null
        return Advice(task, switchTo, suggest)
    }
}

/** Photo/video chats: how much memory to ask for, and how many frames at what size fit in it. */
object VisionCtx {
    class Plan(val frames: Int, val imgMax: Int, val vidMax: Int)

    /** The Memory setting, or when it's Auto a roomier 16K (8K on devices with less RAM). */
    fun ctx(memSetting: Int, ramGb: Double) = if (memSetting > 0) memSetting else if (ramGb >= 7.5) 16384 else 8192

    fun plan(ctx: Int, nImages: Int, nVideos: Int, textChars: Int): Plan {
        val frames = if (ctx >= 16384) 6 else if (ctx >= 8192) 4 else 3
        val count = maxOf(1, nImages + nVideos * frames)
        val budget = maxOf(600.0, ctx * 0.55 - textChars / 3.5)
        // About one token per 28x28 pixel patch; 2.5x margin because models count image tokens differently.
        val side = (28 * Math.sqrt(budget / count / 2.5)).toInt()
        return Plan(frames, side.coerceIn(224, 1024), side.coerceIn(224, 768))
    }

    /** "request (4304 tokens) exceeds the available context size (4096 tokens)" -> plain words and what to do. */
    fun friendly(err: String): String? {
        if (!Regex("exceeds the available context|context size|context length|context window|too many tokens", RegexOption.IGNORE_CASE).containsMatchIn(err)) return null
        val m = Regex("\\((\\d+) tokens\\)[^(]*\\((\\d+) tokens\\)").find(err)
        return "That was more than the model can hold in its memory" + (if (m != null) " (it needed ${m.groupValues[1]} tokens, ${m.groupValues[2]} were available)" else "") +
            ". Open Settings and set Memory to 16K or 32K (bigger uses more RAM), then send it again."
    }
}
