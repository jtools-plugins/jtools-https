package com.lhstack.https

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.ProjectManager

class OpenJToolsHttpSearchAction : AnAction(
    "JTools Http Search",
    "打开 JTools Http Search 搜索面板",
    HttpIcons.pluginTab
) {
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null || ProjectManager.getInstance().openProjects.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        HttpSearchEverywhereOpener.open(project)
    }
}
