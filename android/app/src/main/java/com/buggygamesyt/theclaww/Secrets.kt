package com.buggygamesyt.theclaww

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small secrets (the GitHub token), encrypted with a key that never leaves the Android Keystore, in app-private prefs. */
object Secrets {
    private const val ALIAS = "claww_secrets"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("claww_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    fun put(ctx: Context, name: String, value: String?) {
        if (value.isNullOrEmpty()) { prefs(ctx).edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(value.toByteArray())
        prefs(ctx).edit().putString(name, Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
    }

    fun get(ctx: Context, name: String): String? = try {
        prefs(ctx).getString(name, null)?.let { s ->
            val (iv, enc) = s.split(':', limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(enc, Base64.NO_WRAP)))
        }
    } catch (_: Exception) { null }
}

/** GitHub's remote MCP server in claw's settings.json (CLAW_CONFIG_HOME), added when a token is set and removed when cleared. */
object GitHubMcp {
    const val URL = "https://api.githubcopilot.com/mcp/"
    const val TOKEN = "github_token"

    fun configHome(ctx: Context) = File(ctx.filesDir, "home/.claw")

    fun has(ctx: Context) = !Secrets.get(ctx, TOKEN).isNullOrBlank()

    /** Saves (or with null/blank, removes) the token and updates claw's settings. Throws with a readable message on failure. */
    fun set(ctx: Context, token: String?) {
        val t = token?.trim().orEmpty()
        if (t.any { it.isWhitespace() }) throw Exception("that doesn't look like a token (it has spaces in it)")
        val f = File(configHome(ctx), "settings.json")
        val text = if (f.exists()) f.readText() else ""
        val o = if (text.isBlank()) JSONObject() else try { JSONObject(text) } catch (e: Exception) {
            throw Exception("claw's settings file isn't valid JSON, so it was left alone")
        }
        if (t.isNotEmpty()) {
            val servers = o.optJSONObject("mcpServers") ?: JSONObject().also { o.put("mcpServers", it) }
            servers.put("github", JSONObject().put("type", "http").put("url", URL)
                .put("headers", JSONObject().put("Authorization", "Bearer $t")))
        } else {
            o.optJSONObject("mcpServers")?.let { s -> s.remove("github"); if (s.length() == 0) o.remove("mcpServers") }
        }
        if (t.isEmpty() && o.length() == 0 && !f.exists()) { Secrets.put(ctx, TOKEN, null); return }
        f.parentFile?.mkdirs()
        f.writeText(o.toString(2))
        f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true)
        Secrets.put(ctx, TOKEN, t.ifEmpty { null })
    }
}
