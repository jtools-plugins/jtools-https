package com.lhstack.https

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer

object HttpHistoryStorage {
    private const val HISTORY_KEY = "jtools.http.history.v1"
    private const val MAX_HISTORY = 200
    private const val MAX_PERSISTED_BODY_CHARS = 200_000
    private const val MAX_PERSISTED_BASE64_CHARS = 200_000

    fun load(project: Project): MutableList<HttpHistoryEntry> {
        val raw = PropertiesComponent.getInstance(project).getValue(HISTORY_KEY) ?: return mutableListOf()
        return try {
            val element = JDOMUtil.load(raw)
            val state = XmlSerializer.deserialize(element, HttpHistoryState::class.java)
            state.entries
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(project: Project, entries: List<HttpHistoryEntry>) {
        val trimmed = if (entries.size > MAX_HISTORY) entries.take(MAX_HISTORY) else entries
        val safeEntries = trimmed.map { entry ->
            val response = entry.response ?: return@map entry
            val safeResponse = trimResponse(response)
            if (safeResponse == response) entry else entry.copy(response = safeResponse)
        }
        val state = HttpHistoryState(entries = safeEntries.toMutableList())
        val element = XmlSerializer.serialize(state)
        val xml = JDOMUtil.writeElement(element)
        PropertiesComponent.getInstance(project).setValue(HISTORY_KEY, xml)
    }

    fun append(project: Project, entry: HttpHistoryEntry) {
        val entries = load(project)
        entries.add(0, entry)
        if (entries.size > MAX_HISTORY) {
            entries.subList(MAX_HISTORY, entries.size).clear()
        }
        save(project, entries)
    }

    fun clear(project: Project) {
        PropertiesComponent.getInstance(project).unsetValue(HISTORY_KEY)
    }

    private fun trimResponse(response: HttpResponseSnapshot): HttpResponseSnapshot {
        val (body, bodyTrimmed) = trimValue(response.body, MAX_PERSISTED_BODY_CHARS)
        val (base64, base64Trimmed) = trimValue(response.bodyBase64, MAX_PERSISTED_BASE64_CHARS)
        if (!bodyTrimmed && !base64Trimmed) {
            return response
        }
        return response.copy(
            body = body,
            bodyBase64 = base64,
            bodyTruncated = response.bodyTruncated || bodyTrimmed || base64Trimmed
        )
    }

    private fun trimValue(value: String?, limit: Int): Pair<String?, Boolean> {
        val raw = value ?: return null to false
        if (raw.length <= limit) {
            return raw to false
        }
        return raw.take(limit) + "\n\n[内容过大，已截断保存]" to true
    }
}
