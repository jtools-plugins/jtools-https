package com.lhstack.https

import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

object HttpPluginContext {
    private data class ProjectState(
        var panel: HttpClientPanel? = null,
        var openPage: Runnable? = null,
        var port: Int? = null,
        var pendingTabId: Long? = null
    )

    private val states = ConcurrentHashMap<Project, ProjectState>()

    fun setOpenPage(project: Project, openPage: Runnable?) {
        val state = states.computeIfAbsent(project) { ProjectState() }
        state.openPage = openPage
    }

    fun attachPanel(project: Project, panel: HttpClientPanel) {
        val state = states.computeIfAbsent(project) { ProjectState() }
        state.panel = panel
        val pending = state.pendingTabId ?: return
        state.pendingTabId = null
        SwingUtilities.invokeLater { panel.selectCallTabById(pending) }
    }

    fun detachPanel(project: Project, dispose: Boolean) {
        val state = states[project] ?: return
        if (dispose) {
            state.panel?.disposePanel()
        }
        state.panel = null
    }

    fun updatePort(project: Project, port: Int) {
        states.computeIfAbsent(project) { ProjectState() }.port = port
    }

    fun getPort(project: Project): Int? {
        return states[project]?.port
    }

    fun addSample(project: Project, draft: HttpRequestDraft, title: String? = null) {
        val state = states.computeIfAbsent(project) { ProjectState() }
        val panel = state.panel
        if (panel != null) {
            SwingUtilities.invokeLater { panel.addCallTab(draft, title) }
        } else {
            val sortIndex = HttpCallTabStorage.loadTabs(project).size
            val tab = HttpCallTab(
                title = title ?: "${draft.method} ${draft.url}",
                draft = draft,
                sortIndex = sortIndex
            )
            HttpCallTabStorage.insertTab(project, tab)
            state.pendingTabId = tab.id
        }
    }

    fun openPanel(project: Project) {
        val action = states[project]?.openPage ?: return
        SwingUtilities.invokeLater { action.run() }
    }

    fun updateSettings(project: Project, settings: HttpUiSettings) {
        HttpUiSettingsStore.save(project, settings)
        val sanitized = HttpUiSettingsStore.load(project)
        val panel = states[project]?.panel ?: return
        SwingUtilities.invokeLater { panel.applySettings(sanitized) }
    }

    fun removeProject(project: Project) {
        states.remove(project)
        HttpUiSettingsStore.clearCache(project)
        HttpEndpointLocator.clearCache(project)
    }
}
