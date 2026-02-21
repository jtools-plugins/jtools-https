package com.lhstack.https

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.ProjectManager

class JToolsHttpSearchContributorFactory :
    SearchEverywhereContributorFactory<JToolsHttpSearchContributor.SearchItem> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<JToolsHttpSearchContributor.SearchItem> {
        return JToolsHttpSearchContributor(initEvent.project)
    }

    override fun isAvailable(project: com.intellij.openapi.project.Project?): Boolean {
        return project != null || ProjectManager.getInstance().openProjects.isNotEmpty()
    }
}
