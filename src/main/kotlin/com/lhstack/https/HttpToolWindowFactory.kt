package com.lhstack.https

import com.intellij.execution.ExecutionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.messages.MessageBusConnection

class HttpToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HttpClientPanel(project)
        HttpPluginContext.attachPanel(project, panel)
        HttpPluginContext.setOpenPage(project) { toolWindow.show(null) }

        val connection: MessageBusConnection = project.messageBus.connect(toolWindow.disposable)
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, ProjectPortListener(project, null))

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        Disposer.register(toolWindow.disposable) {
            HttpPluginContext.detachPanel(project, true)
            HttpPluginContext.setOpenPage(project, null)
            HttpPluginContext.removeProject(project)
        }
    }
}
