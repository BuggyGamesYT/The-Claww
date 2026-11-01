package com.buggygamesyt.theclaww

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Speech to text that works without any phone speech service (GrapheneOS and similar):
 * records 16 kHz mono audio and transcribes it on the device with the bundled whisper.cpp CLI
 * and a small English model that is downloaded on first use.
 * Falls back to the system SpeechRecognizer only when whisper isn't in this build.
 */
class Speech(private val ctx: Context) {
    companion object {
        const val RATE = 16000
        const val MODEL_NAME = "ggml-base.en-q5_1.bin"
        const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"
    }

    private val bin get() = File(ctx.applicationInfo.nativeLibraryDir, "libwhispercli.so")
    val modelFile get() = File(ctx.filesDir, "whisper/$MODEL_NAME")
    fun whisperAvailable() = bin.exists()
    fun systemAvailable() = try { SpeechRecognizer.isRecognitionAvailable(ctx) } catch (_: Exception) { false }

    // ---------- whisper ----------
    /** Downloads the model if needed (blocking). [progress] gets 0..100, or -1 when the size is unknown. */
    fun ensureModel(progress: (Int) -> Unit) {
        val dest = modelFile
        if (dest.exists() && dest.length() > 1_000_000) return
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "$MODEL_NAME.part")
        val c = URL(MODEL_URL).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "TheClaww (android)")
        if (c.responseCode !in 200..299) throw Exception("download failed (HTTP ${c.responseCode})")
        val total = c.contentLengthLong
        var done = 0L; var last = -1
        c.inputStream.use { i ->
            part.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != last) { last = pct; progress(pct) }
                }
            }
        }
        if (done < 1_000_000 || (total > 0 && done < total)) { part.delete(); throw Exception("the download was cut short") }
        if (!part.renameTo(dest)) throw Exception("couldn't save the model")
    }

    @Volatile var recording = false; private set
    private var rec: AudioRecord? = null
    private var pcm = ByteArrayOutputStream()
    private var reader: Thread? = null

    /** Starts recording 16 kHz mono 16-bit audio. Needs the RECORD_AUDIO permission. */
    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min, RATE) // ~0.5 s
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw Exception("the microphone couldn't be opened") }
        pcm = ByteArrayOutputStream()
        rec = r; recording = true
        r.startRecording()
        reader = thread {
            val buf = ByteArray(size)
            while (recording) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
        }
    }

    /** Stops recording and returns the raw 16-bit PCM. */
    fun stop(): ByteArray {
        recording = false
        try { reader?.join(1500) } catch (_: Exception) {}
        rec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        rec = null; reader = null
        return synchronized(pcm) { pcm.toByteArray() }
    }

    /** Runs whisper on the recording and returns the words (blocking). */
    fun transcribe(data: ByteArray): String {
        if (data.size < RATE * 2 * 3 / 10) throw Exception("that was too short. Tap the mic, speak, then tap it again")
        val wav = File(ctx.cacheDir, "speech.wav")
        wav.outputStream().use { o -> o.write(wavHeader(data.size)); o.write(data) }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val pb = ProcessBuilder(bin.absolutePath, "-m", modelFile.absolutePath, "-f", wav.absolutePath,
            "-nt", "-np", "-l", "en", "-t", threads.toString())
            .redirectError(File(ctx.cacheDir, "whisper.log"))
        pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
        val p = pb.start(); p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        wav.delete()
        if (code != 0) {
            val log = try { File(ctx.cacheDir, "whisper.log").readLines().lastOrNull { it.isNotBlank() }?.trim() } catch (_: Exception) { null }
            throw Exception("whisper failed (${log ?: "exit code $code"})")
        }
        return clean(out.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" "))
    }

    /** Drops whisper's non-speech tags like [BLANK_AUDIO] or (music). */
    private fun clean(s: String) = s.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun wavHeader(len: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + len); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1.toShort()); b.putShort(1.toShort())
        b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2.toShort()); b.putShort(16.toShort())
        b.put("data".toByteArray()); b.putInt(len)
        return b.array()
    }

    // ---------- fallback: the system speech service (only when whisper isn't in this build) ----------
    private var sr: SpeechRecognizer? = null

    /** Must be called on the main thread. [onText] gets the words, or null with an error message. */
    fun startSystem(onText: (String?, String?) -> Unit) {
        val r = SpeechRecognizer.createSpeechRecognizer(ctx); sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finishSystem(); onText(t ?: "", null)
            }
            override fun onError(error: Int) { finishSystem(); onText(null, if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "no words were heard" else "speech service error $error") }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    fun stopSystem() { try { sr?.stopListening() } catch (_: Exception) {} }
    private fun finishSystem() { try { sr?.destroy() } catch (_: Exception) {}; sr = null }
}
