package com.lhstack.https

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class HttpScriptScope {
    GLOBAL,
    PROJECT,
    MODULE,
    INTERFACE
}

enum class HttpScriptPhase {
    PRE,
    POST
}

@Tag("script-entry")
data class HttpScopedScriptEntry(
    @Attribute var id: String = UUID.randomUUID().toString(),
    @Attribute var name: String = "",
    @Attribute var enabled: Boolean = true,
    var content: String = ""
)

@Tag("script-scope-state")
data class HttpScopedScriptState(
    @XCollection(style = XCollection.Style.v2, elementName = "entry")
    var entries: MutableList<HttpScopedScriptEntry> = mutableListOf()
)

object HttpScopedScriptStore {
    private const val GLOBAL_PRE_KEY = "jtools.https.script.global.pre"
    private const val GLOBAL_POST_KEY = "jtools.https.script.global.post"
    private const val PROJECT_PRE_KEY = "jtools.https.script.project.pre"
    private const val PROJECT_POST_KEY = "jtools.https.script.project.post"
    private const val MODULE_PRE_PREFIX = "jtools.https.script.module.pre."
    private const val MODULE_POST_PREFIX = "jtools.https.script.module.post."

    fun loadGlobal(phase: HttpScriptPhase): MutableList<HttpScopedScriptEntry> {
        return decode(PropertiesComponent.getInstance().getValue(globalKey(phase)).orEmpty())
    }

    fun saveGlobal(phase: HttpScriptPhase, scripts: List<HttpScopedScriptEntry>) {
        PropertiesComponent.getInstance().setValue(globalKey(phase), encode(normalize(scripts)))
    }

    fun loadProject(project: Project, phase: HttpScriptPhase): MutableList<HttpScopedScriptEntry> {
        return decode(PropertiesComponent.getInstance(project).getValue(projectKey(phase)).orEmpty())
    }

    fun saveProject(project: Project, phase: HttpScriptPhase, scripts: List<HttpScopedScriptEntry>) {
        PropertiesComponent.getInstance(project).setValue(projectKey(phase), encode(normalize(scripts)))
    }

    fun loadModule(project: Project, moduleName: String, phase: HttpScriptPhase): MutableList<HttpScopedScriptEntry> {
        val key = moduleKey(moduleName, phase) ?: return mutableListOf()
        return decode(PropertiesComponent.getInstance(project).getValue(key).orEmpty())
    }

    fun saveModule(project: Project, moduleName: String, phase: HttpScriptPhase, scripts: List<HttpScopedScriptEntry>) {
        val key = moduleKey(moduleName, phase) ?: return
        PropertiesComponent.getInstance(project).setValue(key, encode(normalize(scripts)))
    }

    private fun globalKey(phase: HttpScriptPhase): String {
        return when (phase) {
            HttpScriptPhase.PRE -> GLOBAL_PRE_KEY
            HttpScriptPhase.POST -> GLOBAL_POST_KEY
        }
    }

    private fun projectKey(phase: HttpScriptPhase): String {
        return when (phase) {
            HttpScriptPhase.PRE -> PROJECT_PRE_KEY
            HttpScriptPhase.POST -> PROJECT_POST_KEY
        }
    }

    private fun moduleKey(moduleName: String, phase: HttpScriptPhase): String? {
        val normalized = moduleName.trim()
        if (normalized.isBlank()) {
            return null
        }
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8)
        val prefix = when (phase) {
            HttpScriptPhase.PRE -> MODULE_PRE_PREFIX
            HttpScriptPhase.POST -> MODULE_POST_PREFIX
        }
        return prefix + encoded
    }

    private fun normalize(scripts: List<HttpScopedScriptEntry>): MutableList<HttpScopedScriptEntry> {
        val normalized = mutableListOf<HttpScopedScriptEntry>()
        scripts.forEachIndexed { index, script ->
            val id = script.id.trim().ifBlank { UUID.randomUUID().toString() }
            val name = script.name.trim().ifBlank { "脚本${index + 1}" }
            normalized.add(
                HttpScopedScriptEntry(
                    id = id,
                    name = name,
                    enabled = script.enabled,
                    content = script.content
                )
            )
        }
        return normalized
    }

    private fun encode(scripts: List<HttpScopedScriptEntry>): String {
        if (scripts.isEmpty()) {
            return ""
        }
        return runCatching {
            val state = HttpScopedScriptState(entries = scripts.map { it.copy() }.toMutableList())
            val element = XmlSerializer.serialize(state)
            JDOMUtil.writeElement(element)
        }.getOrDefault("")
    }

    private fun decode(raw: String): MutableList<HttpScopedScriptEntry> {
        if (raw.isBlank()) {
            return mutableListOf()
        }
        return runCatching {
            val element = JDOMUtil.load(raw)
            val state = XmlSerializer.deserialize(element, HttpScopedScriptState::class.java) ?: HttpScopedScriptState()
            normalize(state.entries)
        }.getOrDefault(mutableListOf())
    }
}
