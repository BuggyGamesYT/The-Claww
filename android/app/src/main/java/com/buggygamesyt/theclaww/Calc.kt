package com.buggygamesyt.theclaww

import java.math.BigInteger

/** Exact calculator: plain arithmetic is answered by the app (models guess at big numbers). Same as desktop. */
object Calc {
    private class Q(n: BigInteger, d: BigInteger) {
        val n: BigInteger; val d: BigInteger
        init {
            if (d.signum() == 0) throw ArithmeticException("div0")
            var a = n; var b = d
            if (b.signum() < 0) { a = a.negate(); b = b.negate() }
            val g = a.gcd(b).let { if (it.signum() == 0) BigInteger.ONE else it }
            this.n = a / g; this.d = b / g
        }
    }
    private val TEN = BigInteger.TEN

    fun norm(text: String): String? {
        var s = " " + text.lowercase().replace('\u2212', '-').replace('\u2013', '-') + " "
        s = s.replace(Regex("^\\s*(?:hey\\s+|ok\\s+|so\\s+)?(?:please\\s+)?(?:can you\\s+)?(?:tell me\\s+)?(?:what(?:'s| is)|whats|calculate|compute|solve|work out|how much is|evaluate)\\s+"), " ")
        repeat(3) { s = s.replace(Regex("\\s*(?:\\?|\\.|!|=|equals?|equal to|is what|math|in math|exactly)\\s*$"), " ") }
        s = s.replace(Regex("(\\d),(?=\\d{3}\\b)"), "$1")
            .replace(Regex("\\bmultiplied by\\b|\\btimes\\b"), "*").replace(Regex("\\bdivided by\\b|\\bover\\b"), "/")
            .replace(Regex("\\bplus\\b|\\badded to\\b"), "+").replace(Regex("\\bminus\\b"), "-")
            .replace(Regex("\\bto the power of\\b|\\bto the\\b|\\braised to\\b"), "^").replace(Regex("\\bsquared\\b"), "^2").replace(Regex("\\bcubed\\b"), "^3")
            .replace(Regex("\\bmod(?:ulo)?\\b"), "%").replace(Regex("[×xX✕]"), "*").replace("÷", "/").replace("**", "^")
        s = s.trim()
        if (!Regex("^[\\d.\\s+\\-*/%^()]+$").matches(s) || !Regex("\\d\\s*[+\\-*/%^]\\s*[\\d(-]|\\)\\s*[+\\-*/%^]").containsMatchIn(s)) return null
        return s
    }

    private fun eval(s: String): Q {
        val t = Regex("\\d*\\.?\\d+|[+\\-*/%^()]").findAll(s).map { it.value }.toList()
        var i = 0
        fun num(x: String): Q { val a = x.substringBefore('.'); val b = if (x.contains('.')) x.substringAfter('.') else ""
            return Q(BigInteger((a.ifEmpty { "0" }) + b), TEN.pow(b.length)) }
        lateinit var expr: () -> Q
        lateinit var pow: () -> Q
        fun prim(): Q {
            val k = t.getOrNull(i++)
            return when {
                k == "(" -> { val v = expr(); if (t.getOrNull(i++) != ")") throw IllegalArgumentException("paren"); v }
                k == "-" -> { val v = pow(); Q(v.n.negate(), v.d) }
                k == "+" -> pow()
                k != null && k[0].isDigit() || k?.startsWith(".") == true -> num(k!!)
                else -> throw IllegalArgumentException("syntax")
            }
        }
        pow = {
            val b = prim()
            if (t.getOrNull(i) != "^") b else {
                i++; val e = pow()
                if (e.d != BigInteger.ONE) throw IllegalArgumentException("frac pow")
                val n = e.n.abs()
                if (n > BigInteger.valueOf(100000) || (b.n.toString().length + b.d.toString().length).toLong() * n.toLong() > 200000) throw IllegalArgumentException("big")
                val r = Q(b.n.pow(n.toInt()), b.d.pow(n.toInt()))
                if (e.n.signum() < 0) Q(r.d, r.n) else r
            }
        }
        fun term(): Q {
            var v = pow()
            while (t.getOrNull(i) == "*" || t.getOrNull(i) == "/" || t.getOrNull(i) == "%") {
                val op = t[i++]; val w = pow()
                v = when (op) {
                    "*" -> Q(v.n * w.n, v.d * w.d)
                    "/" -> Q(v.n * w.d, v.d * w.n)
                    else -> { if (v.d != BigInteger.ONE || w.d != BigInteger.ONE || w.n.signum() == 0) throw IllegalArgumentException("mod"); Q(v.n.mod(w.n.abs()), BigInteger.ONE) }
                }
            }
            return v
        }
        expr = {
            var v = term()
            while (t.getOrNull(i) == "+" || t.getOrNull(i) == "-") { val op = t[i++]; val w = term()
                v = Q(v.n * w.d + (if (op == "+") w.n else w.n.negate()) * v.d, v.d * w.d) }
            v
        }
        val v = expr(); if (i != t.size) throw IllegalArgumentException("trailing"); return v
    }

    private fun commas(d: String) = d.replace(Regex("\\B(?=(\\d{3})+(?!\\d))"), ",")

    private fun fmt(q: Q): String {
        val neg = q.n.signum() < 0; val n = q.n.abs(); val d = q.d
        val ip = n / d; val sign = if (neg) "-" else ""
        if (d == BigInteger.ONE) return sign + commas(ip.toString())
        var r = n % d; val f = StringBuilder()
        var k = 0
        while (k < 20 && r.signum() != 0) { r *= TEN; f.append((r / d).toString()); r %= d; k++ }
        return (if (r.signum() != 0) "≈ " else "") + sign + commas(ip.toString()) + "." + (if (r.signum() != 0) f.toString() else f.toString().trimEnd('0'))
    }

    private fun pretty(s: String) = s.replace(Regex("\\s+"), "")
        .replace(Regex("\\d+(?:\\.\\d+)?")) { m -> val a = m.value.substringBefore('.'); val b = if (m.value.contains('.')) "." + m.value.substringAfter('.') else ""
            commas(a.replace(Regex("^0+(?=\\d)"), "")) + b }
        .replace("*", " × ").replace("/", " ÷ ").replace("+", " + ").replace(Regex("(\\d|\\))-"), "$1 − ").replace("%", " mod ")

    /** The reply for a message that's just arithmetic, or null. */
    fun reply(text: String): String? {
        val s = norm(text) ?: return null
        return try { val out = fmt(eval(s)); "${pretty(s)} " + if (out.startsWith("≈")) out else "= $out" }
        catch (e: ArithmeticException) { if (e.message == "div0") "That divides by zero, so there's no answer." else null }
        catch (e: Exception) { null }
    }
}
