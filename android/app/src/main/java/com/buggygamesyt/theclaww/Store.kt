package com.buggygamesyt.theclaww

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One chat message. [atts] are absolute paths of attached files. */
/** [error]: the reply was an error (Ollama/claw/API), never sent back to the model as conversation. */
class Msg(val user: Boolean, var text: String, val atts: List<String> = emptyList(), var stopped: Boolean = false, var error: Boolean = false)

/** A conversation; [project] is null for regular (non-project) chats. */
class Conv(val id: String, var title: String, val project: String?, var updated: Long) {
    val msgs = mutableListOf<Msg>()
    var loaded = false
}

class Project(val id: String, var name: String, var notes: String, var updated: Long)

/** Tiny JSON-file store for chats and projects under filesDir/store. */
object Store {
    private var dir: File? = null
    val convs = mutableListOf<Conv>()
    val projects = mutableListOf<Project>()

    fun init(base: File) {
        if (dir != null) return
        val d = File(base, "store").apply { mkdirs() }
        dir = d
        try {
            val a = JSONArray(File(d, "convs.json").readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                convs.add(Conv(o.getString("id"), o.optString("title", "Chat"),
                    if (o.isNull("project")) null else o.optString("project"), o.optLong("updated")))
            }
        } catch (_: Exception) {}
        try {
            val a = JSONArray(File(d, "projects.json").readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                projects.add(Project(o.getString("id"), o.optString("name", "Project"), o.optString("notes", ""), o.optLong("updated")))
            }
        } catch (_: Exception) {}
    }

    private fun saveIndex() {
        val a = JSONArray()
        convs.sortedByDescending { it.updated }.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("project", it.project ?: JSONObject.NULL).put("updated", it.updated))
        }
        File(dir, "convs.json").writeText(a.toString())
    }

    fun saveProjects() {
        val a = JSONArray()
        projects.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("notes", it.notes).put("updated", it.updated)) }
        File(dir, "projects.json").writeText(a.toString())
    }

    fun msgs(c: Conv): MutableList<Msg> {
        if (!c.loaded) {
            c.loaded = true
            try {
                val a = JSONArray(File(dir, "c_${c.id}.json").readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val at = o.optJSONArray("a")
                    c.msgs.add(Msg(o.getBoolean("u"), o.optString("t"),
                        if (at == null) emptyList() else (0 until at.length()).map { at.getString(it) }, o.optBoolean("s"), o.optBoolean("e")))
                }
            } catch (_: Exception) {}
        }
        return c.msgs
    }

    /** A fresh chat; it only appears in lists once it has been saved with a message. */
    fun newConv(project: String?) = Conv(UUID.randomUUID().toString(), "New chat", project, System.currentTimeMillis()).apply { loaded = true }

    @Synchronized fun saveConv(c: Conv) {
        c.updated = System.currentTimeMillis()
        if (convs.none { it.id == c.id }) convs.add(0, c)
        val a = JSONArray()
        c.msgs.forEach { m ->
            a.put(JSONObject().put("u", m.user).put("t", m.text).put("a", JSONArray(m.atts)).put("s", m.stopped).put("e", m.error))
        }
        File(dir, "c_${c.id}.json").writeText(a.toString())
        saveIndex()
        c.project?.let { pid -> projects.firstOrNull { it.id == pid }?.let { it.updated = c.updated; saveProjects() } }
    }

    fun deleteConv(c: Conv) {
        convs.removeAll { it.id == c.id }
        File(dir, "c_${c.id}.json").delete()
        saveIndex()
    }

    fun newProject(name: String): Project {
        val p = Project(UUID.randomUUID().toString(), name.trim().ifEmpty { "Untitled project" }, "", System.currentTimeMillis())
        projects.add(0, p); saveProjects(); return p
    }

    fun deleteProject(p: Project) {
        convs.filter { it.project == p.id }.toList().forEach { deleteConv(it) }
        projects.removeAll { it.id == p.id }; saveProjects()
    }

    fun project(id: String?) = projects.firstOrNull { it.id == id }
    fun regularChats() = convs.filter { it.project == null }.sortedByDescending { it.updated }
    fun projectChats(pid: String) = convs.filter { it.project == pid }.sortedByDescending { it.updated }
}
