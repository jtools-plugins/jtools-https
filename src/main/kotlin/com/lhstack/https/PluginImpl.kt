package com.lhstack.https

import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.LineMarkerProviders
import com.intellij.execution.ExecutionManager
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.MessageBusConnection
import com.lhstack.tools.plugins.FunctionCalling
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.KeyStroke
import javax.swing.Icon
import javax.swing.JComponent

class PluginImpl : IPlugin {
    override fun pluginIcon(): Icon {
        return HttpIcons.plugin
    }

    override fun pluginTabIcon(): Icon {
        return HttpIcons.pluginTab
    }

    override fun pluginName(): String {
        return "HTTP Client"
    }

    override fun pluginDesc(): String {
        return "Scan HTTP endpoints and generate request samples."
    }

    override fun pluginVersion(): String {
        return "v0.0.3"
    }

    override fun openProject(project: Project?, logger: Logger?, openThisPage: Runnable?) {
        if (project == null) {
            return
        }
        HttpPluginContext.setOpenPage(project, openThisPage)
        val connection = project.messageBus.connect()
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, ProjectPortListener(project, logger))
        projectConnections[project] = connection
    }

    override fun closeProject(project: Project?) {
        if (project == null) {
            return
        }
        HttpPluginContext.detachPanel(project, true)
        projectConnections.remove(project)?.let { Disposer.dispose(it) }
        HttpPluginContext.removeProject(project)
    }

    override fun createPanel(project: Project?): JComponent? {
        if (project == null) {
            return null
        }
        val panel = HttpClientPanel(project)
        HttpPluginContext.attachPanel(project, panel)
        return panel
    }

    override fun closePanel(project: Project?, pluginPanel: JComponent?) {
        if (project != null) {
            HttpPluginContext.detachPanel(project, true)
        }
    }

    override fun install() {
        registerLineMarkerProvider()
        registerEditorPopupAction()
        registerSearchEverywhereContributor()
        registerOpenSearchAction()
    }

    override fun unInstall() {
        unregisterOpenSearchAction()
        unregisterLineMarkerProvider()
        unregisterEditorPopupAction()
        unregisterSearchEverywhereContributor()
    }

    override fun supportMultiOpens(): Boolean {
        return false
    }

    override fun functionCallings(project: Project?): List<FunctionCalling> {
        if (project == null) {
            return emptyList()
        }
        return HttpsFunctionCallingRegistry(project).functionCallings()
    }

    override fun tabPanelActions(project: Project?, pluginPanel: JComponent?): List<AnAction?>? {
        val panel = pluginPanel as? HttpClientPanel ?: return emptyList()
        val settingsAction = object : AnAction("设置", "HTTP 客户端设置", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                panel.showSettingsDialog()
            }
        }
        return listOf(settingsAction)
    }

    private fun registerLineMarkerProvider() {
        if (!lineMarkerRegistered.compareAndSet(false, true)) {
            return
        }
        val provider = HttpEndpointLineMarkerProvider()
        lineMarkerProvider = provider
        LineMarkerProviders.getInstance().addExplicitExtension(Language.ANY, provider)
    }

    private fun unregisterLineMarkerProvider() {
        val provider = lineMarkerProvider ?: return
        lineMarkerProvider = null
        lineMarkerRegistered.set(false)
        LineMarkerProviders.getInstance().removeExplicitExtension(Language.ANY, provider)
    }

    companion object {
        private const val ACTION_ADD_TO_CALL_LIST = "jtools.https.addToCallList"
        private const val ACTION_OPEN_HTTP_SEARCH = "jtools.https.openHttpSearch"
        private val lineMarkerRegistered = AtomicBoolean(false)
        private var lineMarkerProvider: LineMarkerProvider? = null
        private val projectConnections = ConcurrentHashMap<Project, MessageBusConnection>()
        private val popupActionRegistered = AtomicBoolean(false)
        private var popupAction: AnAction? = null
        private var popupGroup: DefaultActionGroup? = null
        private val searchEverywhereRegistered = AtomicBoolean(false)
        private var searchEverywhereDisposable: com.intellij.openapi.Disposable? = null
        private val openSearchActionRegistered = AtomicBoolean(false)
        private var openSearchAction: AnAction? = null
        private var openSearchShortcutKeymap: Keymap? = null
        private var openSearchShortcut: KeyboardShortcut? = null
    }

    private fun registerEditorPopupAction() {
        if (!popupActionRegistered.compareAndSet(false, true)) {
            return
        }
        val actionManager = ActionManager.getInstance()
        val action = HttpAddToCallListAction()
        popupAction = action
        if (actionManager.getAction(ACTION_ADD_TO_CALL_LIST) == null) {
            actionManager.registerAction(ACTION_ADD_TO_CALL_LIST, action)
        }
        val group = actionManager.getAction(IdeActions.GROUP_EDITOR_POPUP) as? DefaultActionGroup
        popupGroup = group
        group?.add(action, Constraints.FIRST)
    }

    private fun unregisterEditorPopupAction() {
        val actionManager = ActionManager.getInstance()
        val action = popupAction
        popupAction = null
        popupGroup?.let { group ->
            if (action != null) {
                group.remove(action)
            }
        }
        popupGroup = null
        if (actionManager.getAction(ACTION_ADD_TO_CALL_LIST) != null) {
        actionManager.unregisterAction(ACTION_ADD_TO_CALL_LIST)
        }
        popupActionRegistered.set(false)
    }

    private fun registerSearchEverywhereContributor() {
        if (!searchEverywhereRegistered.compareAndSet(false, true)) {
            return
        }
        val disposable = Disposer.newDisposable("jtools.http.search")
        val factory = JToolsHttpSearchContributorFactory()
        SearchEverywhereContributor.EP_NAME.point.registerExtension(factory, disposable)
        searchEverywhereDisposable = disposable
    }

    private fun unregisterSearchEverywhereContributor() {
        val disposable = searchEverywhereDisposable ?: return
        searchEverywhereDisposable = null
        Disposer.dispose(disposable)
        searchEverywhereRegistered.set(false)
    }

    private fun registerOpenSearchAction() {
        if (!openSearchActionRegistered.compareAndSet(false, true)) {
            return
        }
        val actionManager = ActionManager.getInstance()
        val action = OpenJToolsHttpSearchAction()
        openSearchAction = action
        if (actionManager.getAction(ACTION_OPEN_HTTP_SEARCH) == null) {
            actionManager.registerAction(ACTION_OPEN_HTTP_SEARCH, action)
        }
        // PluginImpl mode may not load plugin.xml shortcuts, so register it to active keymap.
        val keymap = KeymapManager.getInstance()?.activeKeymap
        val shortcut = KeyboardShortcut(KeyStroke.getKeyStroke("ctrl shift S"), null)
        if (keymap != null) {
            val existing = keymap.getShortcuts(ACTION_OPEN_HTTP_SEARCH)
            if (existing.none { it == shortcut }) {
                keymap.addShortcut(ACTION_OPEN_HTTP_SEARCH, shortcut)
                openSearchShortcutKeymap = keymap
                openSearchShortcut = shortcut
            }
        }
    }

    private fun unregisterOpenSearchAction() {
        val keymap = openSearchShortcutKeymap
        val shortcut = openSearchShortcut
        if (keymap != null && shortcut != null) {
            keymap.removeShortcut(ACTION_OPEN_HTTP_SEARCH, shortcut)
        }
        openSearchShortcutKeymap = null
        openSearchShortcut = null
        val actionManager = ActionManager.getInstance()
        if (actionManager.getAction(ACTION_OPEN_HTTP_SEARCH) != null) {
            actionManager.unregisterAction(ACTION_OPEN_HTTP_SEARCH)
        }
        openSearchAction = null
        openSearchActionRegistered.set(false)
    }

}
