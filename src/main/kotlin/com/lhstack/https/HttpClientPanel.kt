package com.lhstack.https

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.json.JsonFileType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.*
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.PsiNavigateUtil
import com.intellij.util.ui.HtmlPanel
import com.intellij.util.ui.JBUI
import com.lhstack.https.component.MultiLanguageTextField
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Authenticator
import java.net.HttpCookie
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import kotlin.math.max

class HttpClientPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val apiRootNode = DefaultMutableTreeNode("接口")
    private val apiTreeModel = DefaultTreeModel(apiRootNode)
    private val apiTree = Tree(apiTreeModel)
    private val apiSearch = JBTextField()
    private val apiContentLayout = CardLayout()
    private val apiContentPanel = JPanel(apiContentLayout)
    private val apiEmptyLabel = JBLabel()
    private val apiGroups = mutableListOf<HttpApiGroup>()
    private val apiRequests = mutableListOf<HttpSavedRequest>()
    private val callTabs = mutableListOf<HttpCallTab>()
    private val callTabInfos = mutableMapOf<Long, TabInfo>()
    private val disposable = Disposer.newDisposable()
    private val callTabsPane: JBTabs = JBTabsFactory.createEditorTabs(project,disposable)
    private val treeNodeFlavor = DataFlavor(
        DataFlavor.javaJVMLocalObjectMimeType + ";class=" + DefaultMutableTreeNode::class.java.name
    )

    private var uiSettings = HttpUiSettingsStore.load(project)
    private var variableTemplateSettings = normalizeVariableTemplateSettings(HttpVariableTemplateSettingsStore.load(project))

    private val methodBox = JComboBox(HTTP_METHODS)
    private val urlField = JBTextField()
    private val timeoutField = JBTextField(uiSettings.defaultTimeoutSeconds.toString())
    private val sendAction = object : AnAction("发送", "发送请求", HttpIcons.send) {
        override fun actionPerformed(e: AnActionEvent) {
            sendCurrentRequest()
        }

        override fun update(e: AnActionEvent) {
            val sending = isSending
            e.presentation.isEnabled = !sending
            e.presentation.description = if (sending) "发送中..." else "发送请求"
        }

        override fun getActionUpdateThread(): ActionUpdateThread {
            return ActionUpdateThread.EDT
        }
    }
    private val cancelAction = object : AnAction("取消", "取消请求", HttpIcons.cancel) {
        override fun actionPerformed(e: AnActionEvent) {
            cancelCurrentRequest()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = isSending
        }

        override fun getActionUpdateThread(): ActionUpdateThread {
            return ActionUpdateThread.EDT
        }
    }
    private val copyCurlAction = simpleAction("复制 cURL", "复制为 cURL 命令", HttpIcons.copy) {
        copyCurl()
    }
    private val saveApiAction = simpleAction("保存接口", "保存当前接口", HttpIcons.save) {
        saveCurrentRequest()
    }
    private val historyAction = simpleAction("历史请求", "查看历史请求", HttpIcons.history) {
        showHistoryDialog()
    }
    private val importApiAction = simpleAction("导入", "导入 OpenAPI/Swagger 接口", HttpIcons.importApi) {
        showImportApiDialog()
    }
    private val exportApiAction = simpleAction("导出", "导出选中接口", HttpIcons.exportApi) {
        showExportApiDialog()
    }
    private val requestHistoryAction = simpleAction("历史", "查看当前请求历史", HttpIcons.historyRequest) {
        showCurrentRequestHistory()
    }
    private val envSettingsAction = simpleAction("环境变量", "配置项目/全局环境变量", HttpIcons.scriptEnv) {
        ScriptEnvDialog(project).show()
    }

    private val requestVarsModel = DefaultTableModel(arrayOf("键", "值", "说明"), 0)
    private val pathParamsModel = DefaultTableModel(arrayOf("键", "值", "说明"), 0)
    private val paramsModel = DefaultTableModel(arrayOf("键", "值", "说明"), 0)
    private val headersModel = DefaultTableModel(arrayOf("键", "值", "说明"), 0)
    private val urlEncodedModel = DefaultTableModel(arrayOf("键", "值", "说明"), 0)
    private val formDataModel = DefaultTableModel(arrayOf("键", "值", "类型"), 0)
    private val requestDocParamsModel = DefaultTableModel(arrayOf("字段", "示例", "说明"), 0)
    private val responseStatusDocsModel = DefaultTableModel(arrayOf("状态码", "说明"), 0)
    private val responseDocParamsModel = DefaultTableModel(arrayOf("字段", "示例", "说明"), 0)
    private val cookiesModel = DefaultTableModel(arrayOf("名称", "值", "域", "路径", "过期时间", "安全", "HttpOnly"), 0)
    private val cookieEntries = mutableListOf<HttpCookieEntry>()
    private lateinit var cookiesTable: JBTable
    private lateinit var formDataTable: JBTable
    private val bodyTypeBox = JComboBox(BODY_TYPES)
    private val bodyCardLayout = CardLayout()
    private val bodyCardPanel = JPanel(bodyCardLayout)
    private val bodyArea = MultiLanguageTextField(JsonFileType.INSTANCE, project)
    private val scriptFileType = resolveScriptFileType()
    private val preScriptEnabledBox = JBCheckBox("启用接口前置脚本", true)
    private val postScriptEnabledBox = JBCheckBox("启用接口后置脚本", true)
    private val preScriptArea = MultiLanguageTextField(scriptFileType, project)
    private val postScriptArea = MultiLanguageTextField(scriptFileType, project)
    private val requestDocBodyEditor = MultiLanguageTextField(JsonFileType.INSTANCE, project)
    private val requestDocExampleModeLabel = JBLabel("请求体类型: 无")
    private val responseDocBodyEditor = MultiLanguageTextField(JsonFileType.INSTANCE, project)
    private val responseDocStatusField = JBTextField()
    private val responseDocContentTypeField = JBTextField("application/json")
    private val responseDocDescriptionField = JBTextField()

    private val responseSummary = JBLabel("暂无响应")
    private val responseRawArea = createViewerField()
    private val responseRenderArea = createViewerField()
    private val responseRenderJsonArea = MultiLanguageTextField(JsonFileType.INSTANCE, project, isViewer = true)
    private val responseRenderXmlArea = MultiLanguageTextField(XmlFileType.INSTANCE, project, isViewer = true)
    private val responseHeadersArea = createViewerField()
    private val responseRequestHeadersArea = createViewerField()
    private val responseRequestSummaryArea = createViewerField()
    private val responseRenderLayout = CardLayout()
    private val responseRenderPanel = JPanel(responseRenderLayout)
    private val responseRenderHtml = ResponseHtmlPanel()
    private val responseRenderImage = JBLabel()
    private val responseRenderInfo = JBLabel()
    private val responseDownloadAction = object : AnAction("保存文件", "保存响应文件", HttpIcons.download) {
        override fun actionPerformed(e: AnActionEvent) {
            saveResponseToFile()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = responseDownloadEnabled
        }

        override fun getActionUpdateThread(): ActionUpdateThread {
            return ActionUpdateThread.EDT
        }
    }
    private var requestActionsToolbar: ActionToolbar? = null
    private var responseActionsToolbar: ActionToolbar? = null
    private var responseDownloadEnabled = false
    private lateinit var responseTabs: JBTabbedPane
    private lateinit var requestResponseSplit: JBSplitter
    private lateinit var responseCollapseButton: JButton
    private var responsePanelCollapsed = false
    private var responseExpandedProportion = 0.55f

    private var currentTab: HttpCallTab? = null
    private val tabResponses = mutableMapOf<Long, HttpResponseSnapshot?>()
    private var isLoading = false
    private var currentFuture: CompletableFuture<HttpResponse<ByteArray>>? = null
    private var currentIndicator: ProgressIndicator? = null
    private var isSending = false
    private val cookieDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private var currentResponse: HttpResponseSnapshot? = null
    private var renderToken: String? = null
    private var responseVersion = 0
    private var rawRenderedVersion = -1
    private var renderRenderedVersion = -1
    private var headersRenderedVersion = -1
    private var requestHeadersRenderedVersion = -1
    private var requestInfoRenderedVersion = -1
    private var renderInFlightVersion = -1
    private var nextTempTabId = -1L
    private val pendingTabInserts = mutableSetOf<Long>()
    private var committingEditors = false
    private var persistingCurrentTab = false
    private var draftPersistTimer: javax.swing.Timer? = null
    private val jsonMapper = ObjectMapper()
    private var syncingDocEditors = false
    private var docPreviewRefreshScheduled = false
    private var templateDecorationsRefreshScheduled = false
    private var templatePreviewContext: TemplatePreviewContext? = null
    private val templateAwareTables = mutableListOf<JBTable>()
    private val tableByModel = IdentityHashMap<DefaultTableModel, JTable>()
    private val defaultUrlFieldForeground: Color = urlField.foreground
    private val defaultUrlFieldBackground: Color = urlField.background

    init {
        border = JBUI.Borders.empty(8)
        buildHistoryPanel()
        setupDocPreviewListeners()
        setupDraftPersistenceListeners()
        loadApiData()
        loadCallTabs()
        loadCookies()
    }

    private fun setupDocPreviewListeners() {
        val refresh = {
            scheduleRequestDocPreviewRefresh()
        }
        listOf(urlEncodedModel, formDataModel, requestDocParamsModel)
            .forEach { model ->
                model.addTableModelListener { refresh() }
            }
        bodyArea.document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                refresh()
            }
        })
        requestDocBodyEditor.document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                if (!isLoading) {
                    syncRequestBodyFromDocEditor()
                }
            }
        })
        responseDocBodyEditor.document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                if (!isLoading) {
                    syncResponseDocParamsFromBody()
                }
            }
        })
        bodyTypeBox.addActionListener { refresh() }
    }

    private fun scheduleRequestDocPreviewRefresh() {
        if (docPreviewRefreshScheduled) {
            return
        }
        docPreviewRefreshScheduled = true
        SwingUtilities.invokeLater {
            docPreviewRefreshScheduled = false
            if (isLoading || committingEditors || syncingDocEditors) {
                return@invokeLater
            }
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            val focusedTable = SwingUtilities.getAncestorOfClass(JTable::class.java, focusOwner) as? JTable
            if (focusedTable?.isEditing == true) {
                return@invokeLater
            }
            refreshRequestDocExampleFromUi()
        }
    }

    private fun setupDraftPersistenceListeners() {
        fun onDraftChanged() {
            if (isLoading) {
                return
            }
            invalidateTemplatePreviewContext()
            schedulePersistCurrentTab()
            scheduleTemplateDecorationsRefresh()
        }

        listOf(
            requestVarsModel,
            pathParamsModel,
            paramsModel,
            headersModel,
            urlEncodedModel,
            formDataModel,
            requestDocParamsModel,
            responseStatusDocsModel,
            responseDocParamsModel
        ).forEach { model ->
            model.addTableModelListener { onDraftChanged() }
        }

        val swingListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                if (e.document === urlField.document) {
                    updateUrlTemplateDecoration()
                }
                onDraftChanged()
            }

            override fun removeUpdate(e: DocumentEvent) {
                if (e.document === urlField.document) {
                    updateUrlTemplateDecoration()
                }
                onDraftChanged()
            }

            override fun changedUpdate(e: DocumentEvent) {
                if (e.document === urlField.document) {
                    updateUrlTemplateDecoration()
                }
                onDraftChanged()
            }
        }
        listOf(urlField, timeoutField, responseDocStatusField, responseDocContentTypeField, responseDocDescriptionField)
            .forEach { field ->
                field.document.addDocumentListener(swingListener)
            }

        val editorListener = object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                onDraftChanged()
            }
        }
        listOf(bodyArea, preScriptArea, postScriptArea, requestDocBodyEditor, responseDocBodyEditor)
            .forEach { editor ->
                editor.document.addDocumentListener(editorListener)
            }
        methodBox.addActionListener { onDraftChanged() }
        bodyTypeBox.addActionListener { onDraftChanged() }
    }

    private fun schedulePersistCurrentTab() {
        if (draftPersistTimer == null) {
            draftPersistTimer = javax.swing.Timer(
                350,
                java.awt.event.ActionListener { flushPersistCurrentTab() }
            ).apply {
                isRepeats = false
            }
        }
        draftPersistTimer?.restart()
    }

    private fun flushPersistCurrentTab() {
        if (isLoading || persistingCurrentTab) {
            return
        }
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val focusedTable = SwingUtilities.getAncestorOfClass(JTable::class.java, focusOwner) as? JTable
        if (focusedTable?.isEditing == true) {
            if (isShowing) {
                schedulePersistCurrentTab()
            }
            return
        }
        persistingCurrentTab = true
        try {
            persistCurrentTab()
        } finally {
            persistingCurrentTab = false
        }
    }

    private fun invalidateTemplatePreviewContext() {
        templatePreviewContext = null
    }

    private fun scheduleTemplateDecorationsRefresh() {
        if (templateDecorationsRefreshScheduled) {
            return
        }
        templateDecorationsRefreshScheduled = true
        SwingUtilities.invokeLater {
            templateDecorationsRefreshScheduled = false
            refreshTemplateDecorations()
        }
    }

    private fun refreshTemplateDecorations() {
        updateUrlTemplateDecoration()
        templateAwareTables.forEach { table -> table.repaint() }
    }

    fun disposePanel() {
        flushPersistCurrentTab()
        Disposer.dispose(disposable)
        draftPersistTimer?.stop()
        draftPersistTimer = null
        cancelCurrentRequest()
        currentFuture = null
        currentIndicator = null
        renderToken = null
        resetResponseRenderState()
        currentResponse = null
        isLoading = true
        try {
            callTabs.clear()
            callTabsPane.removeAllTabs()
            currentTab = null
            tabResponses.clear()
            callTabInfos.clear()
            apiGroups.clear()
            apiRequests.clear()
            apiRootNode.removeAllChildren()
            apiTreeModel.reload()
            requestVarsModel.setRowCount(0)
            paramsModel.setRowCount(0)
            headersModel.setRowCount(0)
            urlEncodedModel.setRowCount(0)
            formDataModel.setRowCount(0)
            requestDocParamsModel.setRowCount(0)
            responseStatusDocsModel.setRowCount(0)
            responseDocParamsModel.setRowCount(0)
            cookiesModel.setRowCount(0)
            cookieEntries.clear()
            templateAwareTables.clear()
            tableByModel.clear()
            invalidateTemplatePreviewContext()
            bodyArea.text = ""
            preScriptEnabledBox.isSelected = true
            postScriptEnabledBox.isSelected = true
            preScriptArea.text = ""
            postScriptArea.text = ""
            requestDocBodyEditor.text = ""
            requestDocExampleModeLabel.text = "请求体类型: 无"
            responseDocBodyEditor.text = ""
            responseDocStatusField.text = ""
            responseDocContentTypeField.text = "application/json"
            responseDocDescriptionField.text = ""
            responseRawArea.text = ""
            responseRenderArea.text = ""
            responseRenderJsonArea.text = ""
            responseRenderXmlArea.text = ""
            responseRenderHtml.setHtml("")
            responseRenderImage.icon = null
            responseRenderInfo.text = ""
            responseHeadersArea.text = ""
            responseRequestHeadersArea.text = ""
            setResponseDownloadEnabled(false)
        } finally {
            isLoading = false
            invalidateTemplatePreviewContext()
            scheduleTemplateDecorationsRefresh()
        }
    }

    fun selectCallTabById(tabId: Long) {
        val index = callTabs.indexOfFirst { it.id == tabId }
        if (index >= 0) {
            selectCallTab(index)
        }
    }

    fun showSettingsDialog() {
        val dialog = SettingsDialog(project, uiSettings)
        if (!dialog.showAndGet()) {
            return
        }
        val updated = dialog.toSettings()
        HttpUiSettingsStore.save(project, updated)
        val variableUpdated = dialog.toVariableTemplateSettings()
        HttpVariableTemplateSettingsStore.save(project, variableUpdated)
        variableTemplateSettings = normalizeVariableTemplateSettings(HttpVariableTemplateSettingsStore.load(project))
        applySettings(updated)
        invalidateTemplatePreviewContext()
        scheduleTemplateDecorationsRefresh()
    }

    fun applySettings(updated: HttpUiSettings) {
        val oldDefault = uiSettings.defaultTimeoutSeconds
        val oldLineMarkerEnabled = uiSettings.lineMarkerEnabled
        uiSettings = updated
        if (timeoutField.text.trim() == oldDefault.toString()) {
            timeoutField.text = updated.defaultTimeoutSeconds.toString()
        }
        if (oldLineMarkerEnabled != updated.lineMarkerEnabled) {
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    fun applyVariableTemplateSettings(updated: HttpVariableTemplateSettings) {
        variableTemplateSettings = normalizeVariableTemplateSettings(updated)
        invalidateTemplatePreviewContext()
        scheduleTemplateDecorationsRefresh()
    }

    private fun normalizeVariableTemplateSettings(settings: HttpVariableTemplateSettings): HttpVariableTemplateSettings {
        return settings.copy(
            templateEnabled = true,
            unresolvedPolicy = HttpVariableTemplateSettings.UnresolvedPolicy.KEEP.name,
            unscopedResolveOrder = HttpVariableTemplateSettings.ResolveOrder.REQUEST_PROJECT_GLOBAL.name
        )
    }

    fun addCallTab(draft: HttpRequestDraft, title: String? = null, savedRequestId: Long? = null) {
        val newTab = HttpCallTab(
            id = nextTempTabId--,
            title = title ?: buildTabTitle(draft, savedRequestId),
            savedRequestId = savedRequestId,
            draft = draft,
            sortIndex = callTabs.size
        )
        callTabs.add(newTab)
        val tab = addCallTabUi(newTab)
        callTabsPane.select(tab,true)
//        selectCallTab(callTabs.size - 1)
        persistTabAsync(newTab)
    }

    private fun buildHistoryPanel() {
        apiTree.isRootVisible = false
        apiTree.showsRootHandles = true
        apiTree.rowHeight = JBUI.scale(24)
        apiTree.putClientProperty("JTree.lineStyle", "None")
        apiTree.selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        apiTree.cellRenderer = ApiTreeRenderer()
        apiTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    openSelectedRequest()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showApiPopup(e)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showApiPopup(e)
                }
            }
        })
        apiTree.dragEnabled = true
        apiTree.dropMode = DropMode.ON_OR_INSERT
        apiTree.transferHandler = ApiTreeTransferHandler()

        apiSearch.emptyText.text = "搜索接口"
        apiSearch.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                applyApiFilter()
            }

            override fun removeUpdate(e: DocumentEvent) {
                applyApiFilter()
            }

            override fun changedUpdate(e: DocumentEvent) {
                applyApiFilter()
            }
        })

        val newGroupAction = simpleAction("新建分组", "新建分组", HttpIcons.groupAdd) {
            createGroup(null)
        }

        val apiHeader = JPanel(BorderLayout(10, 0))
        apiHeader.border = JBUI.Borders.empty(0, 2)
        apiHeader.add(JBLabel("接口列表"), BorderLayout.WEST)
        val apiActionsToolbar = buildActionToolbar(
            "HttpApiActionsToolbar",
            listOf(historyAction, importApiAction, exportApiAction, newGroupAction),
            apiHeader
        )
        apiHeader.add(apiActionsToolbar.component, BorderLayout.EAST)

        val apiTop = JPanel(BorderLayout(0, 6))
        apiTop.add(apiHeader, BorderLayout.NORTH)
        apiTop.add(apiSearch, BorderLayout.SOUTH)

        val apiPane = JBScrollPane(apiTree)
        apiPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        apiEmptyLabel.horizontalAlignment = SwingConstants.CENTER
        apiEmptyLabel.foreground = JBColor.GRAY
        apiEmptyLabel.text = "暂无接口"
        val apiEmptyPanel = JPanel(BorderLayout())
        apiEmptyPanel.add(apiEmptyLabel, BorderLayout.CENTER)
        apiContentPanel.add(apiPane, "tree")
        apiContentPanel.add(apiEmptyPanel, "empty")

        val apiPanel = JPanel(BorderLayout(0, 6))
        apiPanel.add(apiTop, BorderLayout.NORTH)
        apiPanel.add(apiContentPanel, BorderLayout.CENTER)
        updateApiEmptyState("")

        val splitPane = JBSplitter(false, 0.2f)
        splitPane.firstComponent = apiPanel
        splitPane.secondComponent = buildRequestPanel()
        splitPane.setDividerWidth(JBUI.scale(6))
        splitPane.setShowDividerControls(true)
        splitPane.setShowDividerIcon(true)
        splitPane.setResizeEnabled(true)
        splitPane.proportion = 0.2f
        add(splitPane, BorderLayout.CENTER)
    }

    private fun buildRequestPanel(): JPanel {
        val requestPanel = JPanel(BorderLayout(0, 8))

        callTabsPane.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                if (isLoading) {
                    return
                }
                val tab = newSelection?.getObject() as? HttpCallTab ?: return
                val index = callTabs.indexOfFirst { it.id == tab.id }
                if (index >= 0) {
                    selectCallTab(index)
                }
            }
        }, project)
        callTabsPane.presentation.isHideTabs = false
        callTabsPane.setPopupGroup(buildTabPopupGroup(), "CallTabsPopup",true)
        callTabsPane.component.minimumSize = JBUI.size(120, 26)
        callTabsPane.component.preferredSize = JBUI.size(120, 26)
        val tabBar = JPanel(BorderLayout(6, 0))
        tabBar.add(callTabsPane.component, BorderLayout.CENTER)
        val newTabAction = simpleAction("新建", "新建请求", HttpIcons.tabAdd) {
            createNewTab()
        }
        val tabActionsToolbar = buildActionToolbar("HttpTabActionsToolbar", listOf(newTabAction), tabBar)
        tabActionsToolbar.component.minimumSize = JBUI.size(32, 32)
        tabActionsToolbar.component.preferredSize = JBUI.size(32, 32)
        tabBar.add(tabActionsToolbar.component, BorderLayout.EAST)

        val requestLine = JPanel(BorderLayout(0, 6))
        val urlRow = JPanel(BorderLayout(6, 0))
        methodBox.isEditable = false
        urlField.emptyText.text = "http://localhost:8080/api"
        urlRow.add(methodBox, BorderLayout.WEST)
        urlRow.add(urlField, BorderLayout.CENTER)
        val locateAction = object : AnAction("定位", "定位到接口", AllIcons.General.Locate) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }

            override fun actionPerformed(e: AnActionEvent) {
                locateEndpointForUrl()
            }
        }
        val locateGroup = DefaultActionGroup(locateAction)
        val locateToolbar = ActionManager.getInstance()
            .createActionToolbar("HttpLocateToolbar", locateGroup, true)
        locateToolbar.component.border = JBUI.Borders.empty(0, 2)
        locateToolbar.targetComponent = urlRow
        urlRow.add(locateToolbar.component, BorderLayout.EAST)
        val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        val timeoutPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        timeoutField.columns = 4
        timeoutPanel.add(JBLabel("超时(s)"))
        timeoutPanel.add(timeoutField)
        actionPanel.add(timeoutPanel)
        requestActionsToolbar = buildActionToolbar(
            "HttpRequestActionsToolbar",
            listOf(saveApiAction, requestHistoryAction, envSettingsAction, sendAction, cancelAction, copyCurlAction),
            requestPanel
        )
        actionPanel.add(requestActionsToolbar!!.component)
        val actionRow = JPanel(BorderLayout())
        actionRow.add(actionPanel, BorderLayout.EAST)
        requestLine.add(urlRow, BorderLayout.NORTH)
        requestLine.add(actionRow, BorderLayout.SOUTH)

        val requestVarsPanel = createKeyValuePanel(requestVarsModel, "接口变量")
        val pathParamsPanel = createKeyValuePanel(pathParamsModel, "路径变量")
        val paramsPanel = createKeyValuePanel(paramsModel, "查询参数")
        val headersPanel = createKeyValuePanel(headersModel, "请求头")
        val bodyPanel = buildBodyPanel()
        val apiDocPanel = buildApiDocPanel()
        val cookiesPanel = createCookiePanel()

        val requestTabs = JBTabbedPane()
        requestTabs.addTab("变量", requestVarsPanel)
        requestTabs.addTab("路径变量", pathParamsPanel)
        requestTabs.addTab("参数", paramsPanel)
        requestTabs.addTab("请求头", headersPanel)
        requestTabs.addTab("请求体", bodyPanel)
        requestTabs.addTab("Cookie", cookiesPanel)
        requestTabs.addTab("前置脚本", createScriptPanel(preScriptArea, preScriptEnabledBox, HttpScriptPhase.PRE))
        requestTabs.addTab("后置脚本", createScriptPanel(postScriptArea, postScriptEnabledBox, HttpScriptPhase.POST))
        requestTabs.addTab("接口文档", apiDocPanel)
        requestTabs.setToolTipTextAt(
            0,
            "支持 {{name}}、{{api.name}}、{{project.name}}、{{global.name}}、{{env.name}}、{{path.id}}；鼠标悬浮可查看解析结果"
        )

        responseRenderHtml.isEditable = false

        responseRenderPanel.add(responseRenderArea, "text")
        responseRenderPanel.add(responseRenderJsonArea, "json")
        responseRenderPanel.add(responseRenderXmlArea, "xml")
        responseRenderPanel.add(JBScrollPane(responseRenderHtml), "html")
        val imagePanel = JPanel(BorderLayout())
        imagePanel.add(responseRenderImage, BorderLayout.CENTER)
        responseRenderPanel.add(JBScrollPane(imagePanel), "image")
        val binaryPanel = JPanel(BorderLayout(0, 8))
        binaryPanel.add(responseRenderInfo, BorderLayout.NORTH)
        responseActionsToolbar = buildActionToolbar(
            "HttpResponseActionsToolbar",
            listOf(responseDownloadAction),
            binaryPanel
        )
        binaryPanel.add(responseActionsToolbar!!.component, BorderLayout.WEST)
        responseRenderPanel.add(binaryPanel, "binary")

        responseTabs = JBTabbedPane()
        responseTabs.addTab("原始", responseRawArea)
        responseTabs.addTab("渲染", responseRenderPanel)
        responseTabs.addTab("响应头", responseHeadersArea)
        responseTabs.addTab("请求头", responseRequestHeadersArea)
        responseTabs.addTab("请求信息", responseRequestSummaryArea)
        responseTabs.addChangeListener {
            renderTabIfNeeded(responseTabs.selectedIndex)
        }

        responseCollapseButton = JButton("收起响应").apply {
            isFocusable = false
            margin = JBUI.insets(2, 8)
            toolTipText = "收起响应区域"
            addActionListener { toggleResponsePanelCollapsed() }
        }
        val responseHeaderActions = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(responseCollapseButton)
        }
        val responseHeader = JPanel(BorderLayout(6, 0))
        responseHeader.add(responseSummary, BorderLayout.CENTER)
        responseHeader.add(responseHeaderActions, BorderLayout.EAST)

        val responsePanel = JPanel(BorderLayout(0, 6))
        responsePanel.add(responseHeader, BorderLayout.NORTH)
        responsePanel.add(responseTabs, BorderLayout.CENTER)

        requestResponseSplit = JBSplitter(true, responseExpandedProportion)
        requestResponseSplit.firstComponent = requestTabs
        requestResponseSplit.secondComponent = responsePanel
        requestResponseSplit.border = JBUI.Borders.empty()
        requestResponseSplit.setDividerWidth(JBUI.scale(6))
        requestResponseSplit.setShowDividerControls(true)
        requestResponseSplit.setShowDividerIcon(true)
        requestResponseSplit.setResizeEnabled(true)
        requestResponseSplit.proportion = responseExpandedProportion
        applyResponsePanelCollapsed(collapsed = false, rememberCurrent = false)

        val headerPanel = JPanel(BorderLayout(0, 6))
        headerPanel.add(tabBar, BorderLayout.NORTH)
        headerPanel.add(requestLine, BorderLayout.SOUTH)

        requestPanel.add(headerPanel, BorderLayout.NORTH)
        requestPanel.add(requestResponseSplit, BorderLayout.CENTER)
        requestPanel.border = JBUI.Borders.empty(0, 8, 0, 0)

        return requestPanel
    }

    private fun loadApiData() {
        apiGroups.clear()
        apiGroups.addAll(HttpApiStorage.loadGroups(project))
        apiRequests.clear()
        apiRequests.addAll(HttpApiStorage.loadRequests(project))
        rebuildApiTree()
    }

    fun reloadApiDataFromStorage() {
        val selected = currentTab
        loadApiData()
        refreshCallTabTitles()
        if (selected != null) {
            val index = callTabs.indexOfFirst { it.id == selected.id }
            if (index >= 0) {
                selectCallTab(index)
            }
        }
    }

    private fun loadCallTabs() {
        callTabs.clear()
        callTabsPane.removeAllTabs()
        tabResponses.clear()
        callTabInfos.clear()
        callTabs.addAll(HttpCallTabStorage.loadTabs(project))
        if (callTabs.isEmpty()) {
            createNewTab()
            return
        }
        callTabs.forEach { addCallTabUi(it) }
        selectCallTab(0)
    }

    private fun loadCookies() {
        cookieEntries.clear()
        cookieEntries.addAll(HttpCookieStorage.load(project))
        refreshCookieTable()
    }

    private fun applyApiFilter() {
        rebuildApiTree(apiSearch.text.trim().lowercase())
    }

    private fun showApiPopup(event: MouseEvent) {
        val path = apiTree.getPathForLocation(event.x, event.y) ?: return
        val alreadySelected = apiTree.selectionPaths?.any { it.lastPathComponent == path.lastPathComponent } == true
        if (!alreadySelected) {
            apiTree.selectionPath = path
        }
        val selectedPathCount = apiTree.selectionPaths?.size ?: 0
        val isMultiSelection = selectedPathCount > 1
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val userObject = node.userObject
        val selectedDirectRequests = selectedRequestNodes()
        val selectedRequestsWithChildren = selectedRequests(includeGroupChildren = true)
        val selectedGroups = selectedGroupNodes(includeChildren = true)
        val menu = JPopupMenu()
        when (userObject) {
            is HttpApiGroup -> {
                if (isMultiSelection) {
                    appendBatchSelectionActions(menu, selectedGroups, selectedRequestsWithChildren)
                } else {
                    val addGroup = JMenuItem("新建子分组")
                    addGroup.addActionListener { createGroup(userObject.id) }
                    menu.add(addGroup)

                    val exportGroup = JMenuItem("导出分组接口")
                    exportGroup.addActionListener { showExportApiDialog(collectRequestsForGroup(userObject.id)) }
                    menu.add(exportGroup)

                    val rename = JMenuItem("重命名")
                    rename.addActionListener { renameGroup(userObject) }
                    menu.add(rename)

                    val delete = JMenuItem("删除")
                    delete.addActionListener { deleteGroup(userObject) }
                    menu.add(delete)
                }
            }
            is HttpSavedRequest -> {
                if (isMultiSelection) {
                    val targets = if (selectedDirectRequests.size > 1) {
                        selectedDirectRequests
                    } else {
                        selectedRequestsWithChildren
                    }
                    appendBatchSelectionActions(menu, selectedGroups, targets)
                } else {
                    val open = JMenuItem("打开")
                    open.addActionListener { openRequestInTab(userObject) }
                    menu.add(open)

                    val export = JMenuItem("导出")
                    export.addActionListener { showExportApiDialog(listOf(userObject)) }
                    menu.add(export)

                    val rename = JMenuItem("重命名")
                    rename.addActionListener { renameRequest(userObject) }
                    menu.add(rename)

                    val delete = JMenuItem("删除")
                    delete.addActionListener { deleteRequest(userObject) }
                    menu.add(delete)
                }
            }
        }
        if (menu.componentCount > 0) {
            menu.show(apiTree, event.x, event.y)
        }
    }

    private fun appendBatchSelectionActions(
        menu: JPopupMenu,
        groups: List<HttpApiGroup>,
        requests: List<HttpSavedRequest>
    ) {
        val targetGroups = groups.filter { it.id > 0 }.distinctBy { it.id }
        val targets = requests.filter { it.id > 0 }.distinctBy { it.id }
        if (targetGroups.isEmpty() && targets.isEmpty()) {
            return
        }
        val exportSelected = JMenuItem("导出选中接口(${targets.size})")
        exportSelected.isEnabled = targets.isNotEmpty()
        exportSelected.addActionListener { showExportApiDialog(targets) }
        menu.add(exportSelected)
        val deleteLabel = buildBatchDeleteMenuLabel(targetGroups.size, targets.size)
        val deleteSelected = JMenuItem(deleteLabel)
        deleteSelected.addActionListener { deleteApiTargets(targetGroups, targets) }
        menu.add(deleteSelected)
    }

    private fun buildBatchDeleteMenuLabel(groupCount: Int, requestCount: Int): String {
        return when {
            groupCount > 0 && requestCount > 0 -> "批量删除选中分组($groupCount)和接口($requestCount)"
            groupCount > 0 -> "批量删除选中分组($groupCount)"
            else -> "批量删除选中接口($requestCount)"
        }
    }

    private fun openSelectedRequest() {
        val node = apiTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val request = node.userObject as? HttpSavedRequest ?: return
        openRequestInTab(request)
    }

    private fun createGroup(parentId: Long?) {
        val name = Messages.showInputDialog(
            project,
            "请输入分组名称",
            "新建分组",
            null
        )?.trim().orEmpty()
        if (name.isBlank()) {
            return
        }
        val sortIndex = nextGroupSortIndex(parentId)
        val group = HttpApiGroup(parentId = parentId, name = name, sortIndex = sortIndex)
        HttpApiStorage.insertGroup(project, group)
        apiGroups.add(group)
        rebuildApiTree()
        selectGroupNode(group.id)
    }

    private fun showImportApiDialog() {
        val dialog = ImportApiDialog(project)
        if (!dialog.showAndGet()) {
            return
        }
        when (dialog.sourceType) {
            HttpApiSpecImportService.SourceType.FILE -> {
                runImportSpecTask("文件导入") {
                    HttpApiSpecImportService.importFromFile(
                        project = project,
                        filePath = dialog.filePath,
                        rootGroupName = null,
                        overwriteExisting = true
                    )
                }
            }
            HttpApiSpecImportService.SourceType.URL -> {
                runImportSpecTask("网络地址导入") {
                    HttpApiSpecImportService.importFromUrl(
                        project = project,
                        url = dialog.url,
                        rootGroupName = null,
                        overwriteExisting = true
                    )
                }
            }
            HttpApiSpecImportService.SourceType.JSON -> {
                runImportSpecTask("JSON 导入") {
                    HttpApiSpecImportService.importFromJson(
                        project = project,
                        json = dialog.json,
                        options = HttpApiSpecImportService.ImportOptions(
                            sourceType = HttpApiSpecImportService.SourceType.JSON,
                            source = "dialog",
                            rootGroupName = null,
                            overwriteExisting = true
                        )
                    )
                }
            }
        }
    }

    private fun runImportSpecTask(title: String, action: () -> HttpApiSpecImportService.ImportResult) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "导入接口文档", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "正在解析并导入接口..."
                val result = action()
                SwingUtilities.invokeLater {
                    reloadApiDataFromStorage()
                    val message = buildString {
                        append("来源: ").append(result.sourceType.name).append("\n")
                        append("规范: ").append(result.detectedSpecType).append("\n")
                        append("总接口: ").append(result.totalEndpoints).append("\n")
                        append("新增分组: ").append(result.createdGroups).append("\n")
                        append("新增接口: ").append(result.createdRequests).append("\n")
                        append("更新接口: ").append(result.updatedRequests).append("\n")
                        append("跳过接口: ").append(result.skippedRequests)
                    }
                    Messages.showInfoMessage(project, message, "$title 完成")
                }
            }

            override fun onThrowable(error: Throwable) {
                SwingUtilities.invokeLater {
                    val message = error.message?.ifBlank { "导入失败" } ?: "导入失败"
                    Messages.showErrorDialog(project, message, title)
                }
            }
        })
    }

    private fun showExportApiDialog(explicitRequests: List<HttpSavedRequest>? = null) {
        val selected = explicitRequests?.filter { it.id > 0 }?.distinctBy { it.id }.orEmpty()
        val targets = if (selected.isNotEmpty()) {
            selected
        } else {
            val fromSelection = selectedRequests(includeGroupChildren = true)
            if (fromSelection.isNotEmpty()) {
                fromSelection
            } else {
                val confirm = Messages.showYesNoDialog(
                    project,
                    "当前未选择接口，是否导出全部已保存接口？",
                    "导出接口",
                    null
                )
                if (confirm != Messages.YES) {
                    return
                }
                apiRequests.filter { it.id > 0 }
            }
        }
        if (targets.isEmpty()) {
            Messages.showInfoMessage(project, "暂无可导出的接口。", "导出接口")
            return
        }
        val dialog = ExportApiDialog(project, targets.size)
        if (!dialog.showAndGet()) {
            return
        }
        val format = dialog.format
        val export = HttpApiDocumentExportService.buildExportContent(
            format = format,
            requests = targets,
            title = dialog.titleValue,
            version = dialog.versionValue,
            serverUrl = dialog.serverUrlValue
        )
        val descriptor = FileSaverDescriptor("导出接口文档", "选择保存位置")
        val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val basePath = project.basePath?.let { Paths.get(it) }
        val wrapper = if (basePath != null) {
            saveDialog.save(basePath, export.defaultFileName)
        } else {
            saveDialog.save(export.defaultFileName)
        }
        val file = wrapper?.file ?: return
        Files.write(file.toPath(), export.bytes)
        val message = "已导出 ${targets.size} 个接口到\n${file.toPath().toAbsolutePath()}\n格式: ${export.format.uppercase()}"
        Messages.showInfoMessage(project, message, "导出完成")
    }

    private fun selectedRequestNodes(): List<HttpSavedRequest> {
        return selectedRequests(includeGroupChildren = false)
    }

    private fun selectedRequests(includeGroupChildren: Boolean): List<HttpSavedRequest> {
        val selectedPaths = apiTree.selectionPaths ?: return emptyList()
        val result = LinkedHashMap<Long, HttpSavedRequest>()
        selectedPaths.forEach { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return@forEach
            when (val userObject = node.userObject) {
                is HttpSavedRequest -> {
                    if (userObject.id > 0) {
                        result[userObject.id] = userObject
                    }
                }
                is HttpApiGroup -> {
                    if (includeGroupChildren && userObject.id > 0) {
                        collectRequestsForGroup(userObject.id).forEach { request ->
                            if (request.id > 0) {
                                result[request.id] = request
                            }
                        }
                    }
                }
            }
        }
        return result.values.toList()
    }

    private fun selectedGroupNodes(includeChildren: Boolean): List<HttpApiGroup> {
        val selectedPaths = apiTree.selectionPaths ?: return emptyList()
        val result = LinkedHashMap<Long, HttpApiGroup>()
        val selectedGroupIds = linkedSetOf<Long>()
        selectedPaths.forEach { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return@forEach
            val group = node.userObject as? HttpApiGroup ?: return@forEach
            if (group.id > 0) {
                selectedGroupIds.add(group.id)
                result[group.id] = group
            }
        }
        if (includeChildren && selectedGroupIds.isNotEmpty()) {
            collectGroupIds(selectedGroupIds).forEach { groupId ->
                if (!result.containsKey(groupId)) {
                    apiGroups.firstOrNull { it.id == groupId }?.let { group -> result[group.id] = group }
                }
            }
        }
        return result.values.toList()
    }

    private fun collectRequestsForGroup(groupId: Long): List<HttpSavedRequest> {
        if (groupId <= 0) {
            return emptyList()
        }
        val groupIds = linkedSetOf(groupId)
        var changed = true
        while (changed) {
            changed = false
            apiGroups.forEach { group ->
                val parentId = group.parentId
                if (parentId != null && groupIds.contains(parentId) && groupIds.add(group.id)) {
                    changed = true
                }
            }
        }
        return apiRequests.filter { request ->
            val gid = request.groupId
            gid != null && groupIds.contains(gid)
        }
    }

    private fun collectGroupIds(seedGroupIds: Collection<Long>): Set<Long> {
        val groupIds = linkedSetOf<Long>()
        seedGroupIds.filter { it > 0 }.forEach { groupIds.add(it) }
        if (groupIds.isEmpty()) {
            return emptySet()
        }
        var changed = true
        while (changed) {
            changed = false
            apiGroups.forEach { group ->
                val parentId = group.parentId
                if (parentId != null && groupIds.contains(parentId) && groupIds.add(group.id)) {
                    changed = true
                }
            }
        }
        return groupIds
    }

    private fun renameGroup(group: HttpApiGroup) {
        val name = Messages.showInputDialog(
            project,
            "请输入分组名称",
            "重命名分组",
            null,
            group.name,
            null
        )?.trim().orEmpty()
        if (name.isBlank() || name == group.name) {
            return
        }
        group.name = name
        HttpApiStorage.updateGroup(project, group)
        rebuildApiTree()
        selectGroupNode(group.id)
    }

    private fun deleteGroup(group: HttpApiGroup) {
        val confirm = Messages.showYesNoDialog(
            project,
            "确定删除分组及其子项？",
            "删除分组",
            null
        )
        if (confirm != Messages.YES) {
            return
        }
        deleteApiTargets(listOf(group), emptyList(), showResultMessage = false, confirmDeletion = false)
    }

    private fun renameRequest(request: HttpSavedRequest) {
        val name = Messages.showInputDialog(
            project,
            "请输入接口名称",
            "重命名接口",
            null,
            request.name,
            null
        )?.trim().orEmpty()
        if (name.isBlank() || name == request.name) {
            return
        }
        request.name = name
        HttpApiStorage.updateRequest(project, request)
        rebuildApiTree()
        selectRequestNode(request.id)
        updateTabsForSavedRequest(request)
    }

    private fun deleteRequest(request: HttpSavedRequest) {
        val confirm = Messages.showYesNoDialog(
            project,
            "确定删除该接口？",
            "删除接口",
            null
        )
        if (confirm != Messages.YES) {
            return
        }
        HttpApiStorage.deleteRequest(project, request.id)
        apiRequests.removeIf { it.id == request.id }
        rebuildApiTree()
        val removedIds = setOf(request.id)
        callTabs.filter { it.savedRequestId != null && removedIds.contains(it.savedRequestId) }.forEach { tab ->
            tab.savedRequestId = null
            tab.title = buildTabTitle(tab.draft, null)
            persistTabAsync(tab)
        }
        refreshCallTabTitles()
    }

    private fun deleteApiTargets(
        groups: List<HttpApiGroup>,
        requests: List<HttpSavedRequest>,
        showResultMessage: Boolean = true,
        confirmDeletion: Boolean = true
    ) {
        val selectedGroups = groups.filter { it.id > 0 }.distinctBy { it.id }
        val selectedGroupIds = collectGroupIds(selectedGroups.map { it.id })
        val selectedRequests = requests.filter { it.id > 0 }.distinctBy { it.id }
        val requestIds = linkedSetOf<Long>()
        selectedRequests.forEach { requestIds.add(it.id) }
        if (selectedGroupIds.isNotEmpty()) {
            apiRequests.forEach { request ->
                val groupId = request.groupId
                if (groupId != null && selectedGroupIds.contains(groupId) && request.id > 0) {
                    requestIds.add(request.id)
                }
            }
        }
        if (selectedGroupIds.isEmpty() && requestIds.isEmpty()) {
            Messages.showInfoMessage(project, "请先在接口列表中选择要删除的分组或接口。", "批量删除")
            return
        }
        val groupCount = selectedGroupIds.size
        val requestCount = requestIds.size
        val detail = when {
            groupCount > 0 && requestCount > 0 -> "$groupCount 个分组和 $requestCount 个接口"
            groupCount > 0 -> "$groupCount 个分组"
            else -> "$requestCount 个接口"
        }
        if (confirmDeletion) {
            val confirm = Messages.showYesNoDialog(
                project,
                "确定删除选中的 $detail？",
                "批量删除",
                null
            )
            if (confirm != Messages.YES) {
                return
            }
        }
        requestIds.forEach { id -> HttpApiStorage.deleteRequest(project, id) }
        selectedGroupIds.forEach { id -> HttpApiStorage.deleteGroup(project, id) }
        apiRequests.removeIf { requestIds.contains(it.id) }
        apiGroups.removeIf { selectedGroupIds.contains(it.id) }
        rebuildApiTree()
        callTabs.filter { it.savedRequestId != null && requestIds.contains(it.savedRequestId) }.forEach { tab ->
            tab.savedRequestId = null
            tab.title = buildTabTitle(tab.draft, null)
            persistTabAsync(tab)
        }
        refreshCallTabTitles()
        if (showResultMessage) {
            Messages.showInfoMessage(project, "已删除 $detail。", "批量删除")
        }
    }

    private fun persistCurrentTab() {
        val tab = currentTab ?: return
        commitPendingEditors()
        val draft = resolveDraft(buildDraftFromUI())
        if (tab.draft == draft) {
            return
        }
        tab.draft = draft
        tab.title = buildTabTitle(draft, tab.savedRequestId)
        updateCallTabTitle(tab)
        persistTabAsync(tab)
    }

    private fun addCallTabUi(tab: HttpCallTab): TabInfo {
        val placeholder = JPanel()
        val title = normalizeTabTitle(tab)
        val info = TabInfo(placeholder).setText(title)
        info.setObject(tab)
        val action = object : AnAction({ "关闭" }, AllIcons.Actions.Close) {
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.icon = AllIcons.Actions.Close
                e.presentation.hoveredIcon = AllIcons.Actions.CloseHovered
                e.presentation.isEnabled = callTabs.contains(tab)
            }
            override fun actionPerformed(p0: AnActionEvent) {
                if (callTabs.contains(tab)) {
                    closeTab(tab)
                }
            }
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        info.setTabLabelActions(DefaultActionGroup(action),"JToolsHttpsRequestListActionTabClose")
        callTabInfos[tab.id] = info
        callTabsPane.addTab(info)
        callTabsPane.component.revalidate()
        callTabsPane.component.repaint()
        return info
    }

    private fun buildTabPopupGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(object : AnAction("删除当前") {
            override fun actionPerformed(e: AnActionEvent) {
                selectedCallTab()?.let { closeTab(it) }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedCallTab() != null
            }
        })
        group.add(object : AnAction("删除左侧") {
            override fun actionPerformed(e: AnActionEvent) {
                val index = selectedTabIndex() ?: return
                closeTabsInRange(0, index - 1)
            }

            override fun update(e: AnActionEvent) {
                val index = selectedTabIndex()
                e.presentation.isEnabled = index != null && index > 0
            }
        })
        group.add(object : AnAction("删除右侧") {
            override fun actionPerformed(e: AnActionEvent) {
                val index = selectedTabIndex() ?: return
                closeTabsInRange(index + 1, callTabs.lastIndex)
            }

            override fun update(e: AnActionEvent) {
                val index = selectedTabIndex()
                e.presentation.isEnabled = index != null && index < callTabs.lastIndex
            }
        })
        group.add(object : AnAction("删除全部") {
            override fun actionPerformed(e: AnActionEvent) {
                closeTabsInRange(0, callTabs.lastIndex)
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = callTabs.isNotEmpty()
            }
        })
        return group
    }

    private fun selectedCallTab(): HttpCallTab? {
        return callTabsPane.selectedInfo?.getObject() as? HttpCallTab
    }

    private fun selectedTabIndex(): Int? {
        val tab = selectedCallTab() ?: return null
        val index = callTabs.indexOfFirst { it.id == tab.id }
        return if (index >= 0) index else null
    }

    private fun closeTabsInRange(start: Int, end: Int) {
        if (start > end || callTabs.isEmpty()) {
            return
        }
        val safeStart = start.coerceAtLeast(0)
        val safeEnd = end.coerceAtMost(callTabs.lastIndex)
        if (safeStart > safeEnd) {
            return
        }
        val toClose = callTabs.subList(safeStart, safeEnd + 1).toList()
        toClose.forEach { closeTab(it) }
    }

    private fun updateCallTabTitle(tab: HttpCallTab) {
        callTabInfos[tab.id]?.text = tab.title
    }

    private fun refreshCallTabTitles() {
        callTabs.forEach { updateCallTabTitle(it) }
    }

    private fun selectCallTab(index: Int) {
        if (index < 0 || index >= callTabs.size) {
            return
        }
        persistCurrentTab()
        val tab = callTabs[index]
        isLoading = true
        try {
            currentTab = tab
            loadTabIntoUI(tab)
            val info = callTabInfos[tab.id]
            if (info != null && callTabsPane.selectedInfo != info) {
                callTabsPane.select(info, true)
            }
        } finally {
            isLoading = false
            invalidateTemplatePreviewContext()
            scheduleTemplateDecorationsRefresh()
        }
    }

    private fun loadTabIntoUI(tab: HttpCallTab) {
        val resolved = resolveDraft(tab.draft)
        if (resolved != tab.draft) {
            tab.draft = resolved
            tab.title = buildTabTitle(resolved, tab.savedRequestId)
            updateCallTabTitle(tab)
            persistTabAsync(tab)
        }
        methodBox.selectedItem = tab.draft.method
        urlField.text = tab.draft.url
        timeoutField.text = sanitizeTimeoutSeconds(tab.draft.timeoutSeconds).toString()
        setTableEntries(requestVarsModel, tab.draft.requestVars)
        setTableEntries(pathParamsModel, tab.draft.pathParams)
        setTableEntries(paramsModel, tab.draft.params)
        setTableEntries(headersModel, tab.draft.headers)
        var bodyType = parseBodyType(tab.draft.bodyType)
        if (bodyType == HttpBodyType.NONE) {
            bodyType = when {
                tab.draft.formFields.isNotEmpty() -> HttpBodyType.FORM_DATA
                tab.draft.urlEncoded.isNotEmpty() -> HttpBodyType.FORM_URLENCODED
                tab.draft.requestBodyParams.isNotEmpty() -> HttpBodyType.JSON
                !tab.draft.body.isNullOrBlank() -> HttpBodyType.JSON
                else -> HttpBodyType.NONE
            }
        }
        selectBodyType(bodyType)
        setTableEntries(urlEncodedModel, tab.draft.urlEncoded)
        setFormFields(tab.draft.formFields)
        setTableEntries(requestDocParamsModel, tab.draft.requestBodyParams)
        val responseStatusDocs = if (tab.draft.responseStatusDocs.isNotEmpty()) {
            tab.draft.responseStatusDocs
        } else {
            val fallbackStatus = tab.draft.responseStatus.trim()
            val fallbackDescription = tab.draft.responseDescription.orEmpty().trim()
            if (fallbackStatus.isNotBlank() || fallbackDescription.isNotBlank()) {
                listOf(HttpKeyValue(key = fallbackStatus, description = fallbackDescription))
            } else {
                emptyList()
            }
        }
        setResponseStatusDocs(responseStatusDocs)
        setTableEntries(responseDocParamsModel, tab.draft.responseParams)
        bodyArea.text = tab.draft.body ?: ""
        if (tab.draft.requestBodyParams.isEmpty() && !tab.draft.body.isNullOrBlank()) {
            parseDocEntriesFromJson(tab.draft.body.orEmpty())?.let { parsed ->
                setTableEntries(requestDocParamsModel, parsed)
            }
        }
        updateRequestDocumentation(tab.draft)
        responseDocBodyEditor.text = tab.draft.responseBody ?: ""
        responseDocBodyEditor.revalidate()
        responseDocBodyEditor.repaint()
        if (tab.draft.responseParams.isEmpty() && !tab.draft.responseBody.isNullOrBlank()) {
            parseDocEntriesFromJson(tab.draft.responseBody.orEmpty())?.let { parsed ->
                setTableEntries(responseDocParamsModel, parsed)
            }
        }
        responseDocStatusField.text = tab.draft.responseStatus.ifBlank { responseStatusDocs.firstOrNull()?.key.orEmpty() }
        responseDocContentTypeField.text = tab.draft.responseContentType.ifBlank { "application/json" }
        responseDocDescriptionField.text = tab.draft.responseDescription ?: responseStatusDocs.firstOrNull()?.description.orEmpty()
        preScriptEnabledBox.isSelected = tab.draft.preScriptEnabled
        postScriptEnabledBox.isSelected = tab.draft.postScriptEnabled
        preScriptArea.text = tab.draft.preScript ?: ""
        postScriptArea.text = tab.draft.postScript ?: ""
        updateResponse(tabResponses[tab.id])
    }

    private fun closeTab(tab: HttpCallTab) {
        val index = callTabs.indexOfFirst { it.id == tab.id }
        if (index < 0) {
            return
        }
        callTabs.removeAt(index)
        callTabInfos.remove(tab.id)?.let { callTabsPane.removeTab(it) }
        tabResponses.remove(tab.id)
        if (tab.id > 0) {
            deleteTabAsync(tab.id)
        }
        callTabs.forEachIndexed { idx, item ->
            item.sortIndex = idx
        }
        HttpCallTabStorage.updateTabs(project, callTabs)
        if (callTabs.isNotEmpty()) {
            selectCallTab(minOf(index, callTabs.size - 1))
        } else {
            createNewTab()
        }
    }

    private fun buildTabTitle(draft: HttpRequestDraft, savedRequestId: Long?): String {
        val savedName = savedRequestId?.let { id ->
            apiRequests.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
        }
        if (!savedName.isNullOrBlank()) {
            return StringUtil.shortenTextWithEllipsis(savedName, HISTORY_LABEL_LIMIT, 0)
        }
        val rawUrl = draft.url.ifBlank { "未命名" }
        val displayUrl = rawUrl.replace(Regex("^https?://"), "")
        val title = "${draft.method} $displayUrl"
        return StringUtil.shortenTextWithEllipsis(title, HISTORY_LABEL_LIMIT, 0)
    }

    private fun normalizeTabTitle(tab: HttpCallTab): String {
        val expected = buildTabTitle(tab.draft, tab.savedRequestId)
        if (tab.title != expected) {
            tab.title = expected
            persistTabAsync(tab)
        }
        return tab.title
    }

    private fun openRequestInTab(request: HttpSavedRequest) {
        if (selectCallTabBySavedRequestId(request.id)) {
            return
        }
        val draft = cloneDraft(request.draft)
        addCallTab(draft, request.name, request.id)
    }

    private fun saveCurrentRequest() {
        val tab = ensureCurrentTab()
        commitPendingEditors()
        val draft = resolveDraft(buildDraftFromUI())
        val existing = tab.savedRequestId?.let { id -> apiRequests.firstOrNull { it.id == id } }
        val dialog = SaveRequestDialog(project, existing, buildGroupOptions())
        if (!dialog.showAndGet()) {
            return
        }
        val name = dialog.requestName.trim()
        if (name.isBlank()) {
            return
        }
        val groupId = dialog.groupId
        val savedRequest = if (existing != null) {
            existing.name = name
            existing.groupId = groupId
            existing.draft = cloneDraft(draft)
            HttpApiStorage.updateRequest(project, existing)
            existing
        } else {
            val request = HttpSavedRequest(
                name = name,
                groupId = groupId,
                draft = cloneDraft(draft),
                sortIndex = nextRequestSortIndex(groupId)
            )
            HttpApiStorage.insertRequest(project, request)
            apiRequests.add(request)
            request
        }
        tab.savedRequestId = savedRequest.id
        tab.title = buildTabTitle(draft, savedRequest.id)
        updateCallTabTitle(tab)
        persistTabAsync(tab)
        rebuildApiTree()
        selectRequestNode(savedRequest.id)
        updateTabsForSavedRequest(savedRequest)
        selectCallTabById(tab.id)
    }

    private fun selectCallTabBySavedRequestId(requestId: Long): Boolean {
        val index = callTabs.indexOfFirst { it.savedRequestId == requestId }
        if (index >= 0) {
            selectCallTab(index)
            return true
        }
        return false
    }

    private fun updateTabsForSavedRequest(request: HttpSavedRequest) {
        callTabs.filter { it.savedRequestId == request.id }.forEach { tab ->
            tab.title = buildTabTitle(tab.draft, request.id)
            updateCallTabTitle(tab)
            persistTabAsync(tab)
        }
    }

    private fun showHistoryDialog() {
        val entries = HttpRequestHistoryStorage.loadAll(project)
        val dialog = HttpHistoryDialog(
            project,
            "历史请求",
            entries,
            { entry -> addCallTabFromHistory(entry) },
            { entry -> HttpRequestHistoryStorage.deleteById(project, entry.id) },
            { HttpRequestHistoryStorage.clearAll(project) }
        )
        dialog.show()
    }

    private fun showCurrentRequestHistory() {
        val tab = currentTab ?: return
        val sourceType = if (tab.savedRequestId != null) HistorySourceType.SAVED else HistorySourceType.TAB
        val sourceId = tab.savedRequestId ?: tab.id
        val entries = HttpRequestHistoryStorage.loadForSource(project, sourceType, sourceId)
        val dialog = HttpHistoryDialog(
            project,
            "请求历史",
            entries,
            { entry -> addCallTabFromHistory(entry) },
            { entry -> HttpRequestHistoryStorage.deleteById(project, entry.id) },
            { HttpRequestHistoryStorage.clearForSource(project, sourceType, sourceId) }
        )
        dialog.show()
    }

    private fun locateEndpointForUrl() {
        val method = (methodBox.selectedItem as? String)?.trim().orEmpty()
        val rawUrl = urlField.text.trim()
        if (rawUrl.isBlank()) {
            Messages.showInfoMessage(project, "请输入请求地址后再定位。", "定位接口")
            return
        }
        val path = extractRequestPath(rawUrl)
        if (path == null) {
            Messages.showInfoMessage(project, "无法解析请求地址，请检查格式。", "定位接口")
            return
        }
        val matches = ReadAction.compute<List<EndpointInfo>, RuntimeException> {
            HttpEndpointLocator.find(project, method, path)
        }
        if (matches.isEmpty()) {
            Messages.showInfoMessage(project, "未找到与 $method $path 匹配的接口。", "定位接口")
            return
        }
        val target = if (matches.size == 1) {
            matches.first()
        } else {
            val options = matches.map { buildEndpointLabel(it) }.toTypedArray()
            val dialog = ChooseEndpointDialog(
                project,
                "发现多个匹配接口，请选择定位目标。",
                options
            )
            dialog.show()
            val selectedIndex = dialog.selectedIndex
            if (selectedIndex < 0 || selectedIndex >= matches.size) return
            matches[selectedIndex]
        }
        if (!target.anchor.isValid) {
            Messages.showInfoMessage(project, "目标接口已失效，请重新索引后再试。", "定位接口")
            return
        }
        PsiNavigateUtil.navigate(target.anchor)
    }

    private fun buildEndpointLabel(endpoint: EndpointInfo): String {
        val methodName = endpoint.psiMethod?.name ?: "router"
        val className = endpoint.psiMethod?.containingClass?.name ?: "unknown"
        return "${endpoint.httpMethod} ${endpoint.path} - $className#$methodName"
    }

    private inner class ChooseEndpointDialog(
        project: Project,
        private val message: String,
        private val options: Array<String>
    ) : DialogWrapper(project) {
        private val list = com.intellij.ui.components.JBList(options)
        var selectedIndex: Int = -1
            private set

        init {
            title = "选择接口"
            list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
            if (options.isNotEmpty()) {
                list.selectedIndex = 0
            }
            list.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2 && list.selectedIndex >= 0) {
                        doOKAction()
                    }
                }
            })
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, 6))
            panel.add(JBLabel(message), BorderLayout.NORTH)
            panel.add(JBScrollPane(list), BorderLayout.CENTER)
            panel.preferredSize = JBUI.size(420, 260)
            return panel
        }

        override fun doOKAction() {
            selectedIndex = list.selectedIndex
            super.doOKAction()
        }
    }

    private fun extractRequestPath(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return null
        }
        val withoutFragment = trimmed.substringBefore('#')
        if (withoutFragment.startsWith("/")) {
            return withoutFragment.substringBefore('?')
        }
        val normalized = if (withoutFragment.contains("://")) {
            withoutFragment
        } else {
            "http://$withoutFragment"
        }
        val schemeIndex = normalized.indexOf("://")
        if (schemeIndex < 0) {
            return null
        }
        val searchFrom = schemeIndex + 3
        val slashIndex = normalized.indexOf('/', searchFrom)
        if (slashIndex < 0) {
            return "/"
        }
        val path = normalized.substring(slashIndex)
        val noQuery = path.substringBefore('?')
        return if (noQuery.isBlank()) "/" else noQuery
    }

    private fun addCallTabFromHistory(entry: HttpRequestHistoryEntry) {
        val draft = cloneDraft(entry.request)
        val savedRequestId = if (entry.sourceType == HistorySourceType.SAVED) entry.sourceId else null
        addCallTab(draft, buildTabTitle(draft, savedRequestId), savedRequestId)
    }

    private fun appendHistory(tab: HttpCallTab, request: HttpRequestDraft, response: HttpResponseSnapshot) {
        val sourceType = if (tab.savedRequestId != null) HistorySourceType.SAVED else HistorySourceType.TAB
        val sourceId = tab.savedRequestId ?: tab.id
        HttpRequestHistoryStorage.append(
            project,
            HttpRequestHistoryEntry(
                sourceType = sourceType,
                sourceId = sourceId,
                request = cloneDraft(request),
                response = response
            )
        )
    }

    private fun cloneDraft(draft: HttpRequestDraft): HttpRequestDraft {
        return draft.copy(
            requestVars = draft.requestVars.map { it.copy() }.toMutableList(),
            pathParams = draft.pathParams.map { it.copy() }.toMutableList(),
            params = draft.params.map { it.copy() }.toMutableList(),
            headers = draft.headers.map { it.copy() }.toMutableList(),
            urlEncoded = draft.urlEncoded.map { it.copy() }.toMutableList(),
            formFields = draft.formFields.map { HttpFormField(it.key, it.value, it.fieldType) }.toMutableList(),
            requestBodyParams = draft.requestBodyParams.map { it.copy() }.toMutableList(),
            body = draft.body,
            preScript = draft.preScript,
            postScript = draft.postScript,
            responseStatus = draft.responseStatus,
            responseContentType = draft.responseContentType,
            responseDescription = draft.responseDescription,
            responseBody = draft.responseBody,
            responseStatusDocs = draft.responseStatusDocs.map { it.copy() }.toMutableList(),
            responseParams = draft.responseParams.map { it.copy() }.toMutableList()
        )
    }

    private fun nextGroupSortIndex(parentId: Long?): Int {
        return apiGroups.filter { it.parentId == parentId }.maxOfOrNull { it.sortIndex }?.plus(1) ?: 0
    }

    private fun nextRequestSortIndex(groupId: Long?): Int {
        return apiRequests.filter { it.groupId == groupId }.maxOfOrNull { it.sortIndex }?.plus(1) ?: 0
    }

    private fun buildGroupOptions(): List<GroupOption> {
        val options = mutableListOf(GroupOption(null, "未分组"))
        val groupMap = apiGroups.associateBy { it.id }
        val sorted = apiGroups.sortedWith(compareBy<HttpApiGroup> { it.parentId ?: 0 }.thenBy { it.sortIndex })
        sorted.forEach { group ->
            val label = buildGroupPath(group, groupMap)
            options.add(GroupOption(group.id, label))
        }
        return options
    }

    private fun buildGroupPath(group: HttpApiGroup, groupMap: Map<Long, HttpApiGroup>): String {
        val names = mutableListOf(group.name)
        var parentId = group.parentId
        while (parentId != null) {
            val parent = groupMap[parentId] ?: break
            names.add(parent.name)
            parentId = parent.parentId
        }
        return names.reversed().joinToString(" / ")
    }

    private fun selectGroupNode(groupId: Long) {
        val node = findNode { obj -> (obj as? HttpApiGroup)?.id == groupId } ?: return
        selectTreeNode(node)
    }

    private fun selectRequestNode(requestId: Long) {
        val node = findNode { obj -> (obj as? HttpSavedRequest)?.id == requestId } ?: return
        selectTreeNode(node)
    }

    private fun selectTreeNode(node: DefaultMutableTreeNode) {
        val path = javax.swing.tree.TreePath(node.path)
        apiTree.selectionPath = path
        apiTree.scrollPathToVisible(path)
    }

    private fun findNode(predicate: (Any?) -> Boolean): DefaultMutableTreeNode? {
        fun walk(node: DefaultMutableTreeNode): DefaultMutableTreeNode? {
            if (predicate(node.userObject)) {
                return node
            }
            val children = node.children()
            while (children.hasMoreElements()) {
                val child = children.nextElement() as DefaultMutableTreeNode
                val match = walk(child)
                if (match != null) {
                    return match
                }
            }
            return null
        }
        return walk(apiRootNode)
    }

    private fun rebuildApiTree(query: String = "") {
        val keyword = query.trim().lowercase()
        val groupsByParent = apiGroups.groupBy { it.parentId }
        val requestsByGroup = apiRequests.groupBy { it.groupId }
        apiRootNode.removeAllChildren()

        fun requestMatches(request: HttpSavedRequest): Boolean {
            if (keyword.isBlank()) {
                return true
            }
            return request.name.lowercase().contains(keyword) ||
                request.draft.url.lowercase().contains(keyword) ||
                request.draft.method.lowercase().contains(keyword)
        }

        fun groupMatches(group: HttpApiGroup): Boolean {
            if (keyword.isBlank()) {
                return true
            }
            return group.name.lowercase().contains(keyword)
        }

        fun groupHasMatch(group: HttpApiGroup): Boolean {
            if (groupMatches(group)) {
                return true
            }
            val childGroups = groupsByParent[group.id].orEmpty()
            if (childGroups.any { groupHasMatch(it) }) {
                return true
            }
            val requests = requestsByGroup[group.id].orEmpty()
            if (requests.any { requestMatches(it) }) {
                return true
            }
            return false
        }

        fun addRequests(parentNode: DefaultMutableTreeNode, groupId: Long?) {
            val requests = requestsByGroup[groupId].orEmpty().sortedBy { it.sortIndex }
            requests.forEach { request ->
                if (requestMatches(request)) {
                    parentNode.add(DefaultMutableTreeNode(request))
                }
            }
        }

        fun addGroups(parentNode: DefaultMutableTreeNode, parentId: Long?) {
            val groups = groupsByParent[parentId].orEmpty().sortedBy { it.sortIndex }
            groups.forEach { group ->
                if (keyword.isNotBlank() && !groupHasMatch(group)) {
                    return@forEach
                }
                val node = DefaultMutableTreeNode(group)
                parentNode.add(node)
                addGroups(node, group.id)
                addRequests(node, group.id)
            }
        }

        addGroups(apiRootNode, null)
        addRequests(apiRootNode, null)
        apiTreeModel.reload()
        updateApiEmptyState(keyword)
        if (keyword.isNotBlank()) {
            expandAll(apiTree)
        }
    }

    private fun updateApiEmptyState(keyword: String) {
        val hasNodes = apiRootNode.childCount > 0
        if (hasNodes) {
            apiContentLayout.show(apiContentPanel, "tree")
            return
        }
        apiEmptyLabel.text = if (keyword.isBlank()) {
            "暂无接口，点击“保存接口”或“新建分组”"
        } else {
            "未找到匹配的接口"
        }
        apiContentLayout.show(apiContentPanel, "empty")
    }

    private fun expandAll(tree: JTree) {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun persistTabAsync(tab: HttpCallTab) {
        val oldId = tab.id
        if (oldId <= 0) {
            if (!pendingTabInserts.add(oldId)) {
                return
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                HttpCallTabStorage.insertTab(project, tab)
                val newId = tab.id
                SwingUtilities.invokeLater {
                    pendingTabInserts.remove(oldId)
                    if (!callTabs.contains(tab)) {
                        deleteTabAsync(newId)
                        return@invokeLater
                    }
                    updateTabId(tab, oldId, newId)
                }
            }
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            HttpCallTabStorage.updateTab(project, tab)
        }
    }

    private fun updateTabId(tab: HttpCallTab, oldId: Long, newId: Long) {
        if (oldId == newId) {
            return
        }
        callTabInfos.remove(oldId)?.let { info ->
            info.setObject(tab)
            callTabInfos[newId] = info
        }
        tabResponses.remove(oldId)?.let { tabResponses[newId] = it }
        updateCallTabTitle(tab)
        ApplicationManager.getApplication().executeOnPooledThread {
            HttpRequestHistoryStorage.updateSourceId(project, oldId, newId)
        }
    }

    private fun deleteTabAsync(tabId: Long) {
        ApplicationManager.getApplication().executeOnPooledThread {
            HttpCallTabStorage.deleteTab(project, tabId)
        }
    }

    private fun buildDraftFromUI(): HttpRequestDraft {
        val method = (methodBox.selectedItem as? String)?.uppercase() ?: "GET"
        val url = urlField.text.trim()
        val timeoutSeconds = resolveTimeoutSeconds(timeoutField.text)
        val requestVars = getTableEntries(requestVarsModel)
        val pathParams = getTableEntries(pathParamsModel)
        val params = getTableEntries(paramsModel)
        val headers = getTableEntries(headersModel)
        val bodyType = selectedBodyType()
        val body = if (bodyType == HttpBodyType.JSON) {
            bodyArea.text.trim().ifBlank { requestDocBodyEditor.text.trim().ifBlank { null } }
        } else {
            null
        }
        val requestBodyParams = getTableEntries(requestDocParamsModel)
        val responseStatus = responseDocStatusField.text.trim()
        val responseDescription = responseDocDescriptionField.text.trim()
        val responseStatusDocs = getResponseStatusDocs().let { rows ->
            if (rows.isNotEmpty()) {
                rows
            } else if (responseStatus.isNotBlank() || responseDescription.isNotBlank()) {
                mutableListOf(HttpKeyValue(key = responseStatus, value = "", description = responseDescription))
            } else {
                mutableListOf()
            }
        }
        val responseParams = getTableEntries(responseDocParamsModel)
        val urlEncoded = getTableEntries(urlEncodedModel)
        val formFields = getFormFields()
        return HttpRequestDraft(
            method = method,
            url = url,
            path = currentTab?.draft?.path ?: "",
            moduleName = currentTab?.draft?.moduleName.orEmpty(),
            timeoutSeconds = timeoutSeconds,
            requestVars = requestVars,
            pathParams = pathParams,
            params = params,
            headers = headers,
            bodyType = bodyType.name,
            urlEncoded = urlEncoded,
            formFields = formFields,
            requestBodyParams = requestBodyParams,
            body = body,
            preScriptEnabled = preScriptEnabledBox.isSelected,
            postScriptEnabled = postScriptEnabledBox.isSelected,
            codeMeta = currentTab?.draft?.codeMeta,
            preScript = preScriptArea.text,
            postScript = postScriptArea.text,
            responseStatus = responseStatus.ifBlank { responseStatusDocs.firstOrNull()?.key.orEmpty() },
            responseContentType = responseDocContentTypeField.text.trim(),
            responseDescription = responseDescription
                .ifBlank { responseStatusDocs.firstOrNull()?.description.orEmpty().trim() }
                .takeIf { it.isNotBlank() },
            responseBody = responseDocBodyEditor.text.trim().ifBlank { null },
            responseStatusDocs = responseStatusDocs,
            responseParams = responseParams
        )
    }

    private fun commitPendingEditors() {
        if (committingEditors) {
            return
        }
        committingEditors = true
        try {
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            val focusedTable = SwingUtilities.getAncestorOfClass(JTable::class.java, focusOwner) as? JTable
            if (focusedTable != null && focusedTable.isEditing) {
                val editor = focusedTable.cellEditor
                if (editor != null && !editor.stopCellEditing()) {
                    editor.cancelCellEditing()
                }
            }
        } finally {
            committingEditors = false
        }
    }

    private fun resolveDraft(draft: HttpRequestDraft): HttpRequestDraft {
        val normalizedUrl = normalizeUrl(draft.url)
        val parts = parseUrl(normalizedUrl)
        val mergedParams = mergeParams(parts.queryParams, draft.params)
        return draft.copy(
            url = parts.baseUrl,
            path = parts.path,
            params = mergedParams
        )
    }

    private fun resolveDraftForRequest(draft: HttpRequestDraft): HttpRequestDraft {
        val templated = HttpVariableTemplateResolver.resolveDraft(project, draft, variableTemplateSettings)
        val normalizedUrl = normalizeUrl(templated.url)
        val parts = parseUrl(normalizedUrl)
        val mergedParams = mergeParams(parts.queryParams, templated.params)
        val resolvedPath = applyPathVariables(parts.path, templated.pathParams)
        val baseUrl = replacePathInBaseUrl(parts.baseUrl, parts.path, resolvedPath)
        val finalUrl = buildUrl(baseUrl, mergedParams)
        return templated.copy(
            url = finalUrl,
            path = resolvedPath,
            params = mergedParams
        )
    }

    private fun sendCurrentRequest() {
        if (isSending) {
            return
        }
        commitPendingEditors()
        persistCookiesFromTable()
        val tab = ensureCurrentTab()
        val draft = resolveDraft(buildDraftFromUI())
        tab.draft = draft
        tab.title = buildTabTitle(draft, tab.savedRequestId)
        updateCallTabTitle(tab)
        persistTabAsync(tab)
        if (urlField.text.trim() != draft.url) {
            urlField.text = draft.url
        }
        if (draft.url.isBlank()) {
            val error = HttpResponseSnapshot(
                status = 0,
                statusText = "URL 为空",
                durationMs = 0,
                sizeBytes = 0,
                headers = mutableListOf(),
                body = "URL 为空"
            )
            updateResponse(error)
            tabResponses[tab.id] = error
            appendHistory(tab, draft, error)
            return
        }
        executeRequest(tab, draft)
    }

    private fun copyCurl() {
        commitPendingEditors()
        val draft = resolveDraft(buildDraftFromUI())
        val runtimeDraft = runCatching { resolveDraftForRequest(draft) }.getOrElse { throwable ->
            Messages.showErrorDialog(
                project,
                throwable.message ?: "变量解析失败",
                "复制 cURL 失败"
            )
            return
        }
        val curl = buildCurlCommand(runtimeDraft)
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(curl), null)
    }

    private fun cancelCurrentRequest() {
        currentIndicator?.cancel()
        currentFuture?.cancel(true)
    }

    private fun executeRequest(tab: HttpCallTab, draft: HttpRequestDraft) {
        setSending(true)
        val start = System.nanoTime()
        val scriptVars = linkedMapOf<String, Any?>()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "HTTP 请求", true) {
            override fun run(indicator: ProgressIndicator) {
                currentIndicator = indicator
                indicator.text = "执行前置脚本..."
                var snapshot: HttpResponseSnapshot? = null
                var cookieMutations: List<CookieMutation> = emptyList()
                var requestDraft = resolveDraftForRequest(draft)
                try {
                    val preScriptResult = runPreScript(requestDraft, scriptVars)
                    if (preScriptResult.error != null) {
                        snapshot = buildErrorSnapshot(
                            preScriptResult.error,
                            start,
                            requestDraft
                        )
                    } else {
                        requestDraft = resolveDraftForRequest(preScriptResult.draft)
                    }
                    if (snapshot == null) {
                        indicator.text = "发送请求..."
                        val request = buildHttpRequest(requestDraft)
                        val client = buildHttpClient(sanitizeTimeoutSeconds(requestDraft.timeoutSeconds))
                        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                        currentFuture = future
                        while (!future.isDone) {
                            if (indicator.isCanceled) {
                                future.cancel(true)
                                throw ProcessCanceledException()
                            }
                            Thread.sleep(100)
                        }
                        val response = future.get()
                        cookieMutations = extractCookieMutations(response)
                        val responseSnapshot = buildResponseSnapshot(response, start)
                        snapshot = responseSnapshot
                        val postScriptResult = runPostScript(requestDraft, responseSnapshot, cookieMutations, scriptVars)
                        snapshot = postScriptResult.snapshot
                        cookieMutations = postScriptResult.cookieMutations
                    }
                } catch (e: ProcessCanceledException) {
                    snapshot = buildErrorSnapshot("请求已取消", start, requestDraft)
                } catch (e: CancellationException) {
                    snapshot = buildErrorSnapshot("请求已取消", start, requestDraft)
                } catch (e: Exception) {
                    snapshot = buildErrorSnapshot(e.message ?: "请求失败", start, requestDraft, e.stackTraceToString())
                } finally {
                    currentFuture = null
                    currentIndicator = null
                }
                val finalSnapshot = snapshot ?: buildErrorSnapshot("请求失败", start, requestDraft)
                val finalCookies = cookieMutations
                val finalDraft = requestDraft
                SwingUtilities.invokeLater {
                    updateResponse(finalSnapshot)
                    tabResponses[tab.id] = finalSnapshot
                    appendHistory(tab, finalDraft, finalSnapshot)
                    if (finalCookies.isNotEmpty()) {
                        applyCookieMutations(finalCookies)
                    }
                    setSending(false)
                }
            }

            override fun onCancel() {
                currentFuture?.cancel(true)
            }
        })
    }

    private fun runPreScript(
        draft: HttpRequestDraft,
        vars: MutableMap<String, Any?>
    ): PreScriptResult {
        val scripts = resolveScopedScripts(draft, HttpScriptPhase.PRE)
        if (scripts.isEmpty()) {
            return PreScriptResult(draft = draft)
        }
        val logger = HttpScriptLogger(project)
        var nextDraft = draft
        scripts.forEach { script ->
            val requestContext = toScriptRequestContext(nextDraft)
            val endpointContext = toScriptEndpointContext(nextDraft)
            val execution = executeScript(script.content, requestContext, null, endpointContext, vars, logger)
            if (execution.isFailure) {
                val throwable = execution.exceptionOrNull()
                val message = scriptErrorMessage("前置脚本失败[${script.label}]", throwable)
                logger.error(message)
                return PreScriptResult(draft = nextDraft, error = message)
            }
            nextDraft = fromScriptRequestContext(nextDraft, requestContext)
        }
        return PreScriptResult(draft = nextDraft)
    }

    private fun runPostScript(
        draft: HttpRequestDraft,
        snapshot: HttpResponseSnapshot,
        cookieMutations: List<CookieMutation>,
        vars: MutableMap<String, Any?>
    ): PostScriptResult {
        val scripts = resolveScopedScripts(draft, HttpScriptPhase.POST)
        if (scripts.isEmpty()) {
            return PostScriptResult(snapshot = snapshot, cookieMutations = cookieMutations)
        }
        val logger = HttpScriptLogger(project)
        val requestContext = toScriptRequestContext(draft)
        val endpointContext = toScriptEndpointContext(draft)
        var nextSnapshot = snapshot
        var nextMutations = cookieMutations
        val errors = mutableListOf<String>()
        scripts.forEach { script ->
            val responseContext = toScriptResponseContext(nextSnapshot, nextMutations)
            val execution = executeScript(script.content, requestContext, responseContext, endpointContext, vars, logger)
            if (execution.isFailure) {
                val throwable = execution.exceptionOrNull()
                val message = scriptErrorMessage("后置脚本失败[${script.label}]", throwable)
                logger.error(message)
                errors.add(message)
            } else {
                nextSnapshot = fromScriptResponseContext(nextSnapshot, responseContext)
                nextMutations = toCookieMutations(responseContext, draft.url)
            }
        }
        if (errors.isNotEmpty()) {
            val combined = errors.joinToString(" | ")
            nextSnapshot = nextSnapshot.copy(
                statusText = if (nextSnapshot.statusText.isBlank()) combined else "${nextSnapshot.statusText} | $combined"
            )
        }
        return PostScriptResult(snapshot = nextSnapshot, cookieMutations = nextMutations)
    }

    private fun resolveScopedScripts(
        draft: HttpRequestDraft,
        phase: HttpScriptPhase
    ): List<ResolvedScopedScript> {
        val scripts = mutableListOf<ResolvedScopedScript>()
        HttpScopedScriptStore.loadGlobal(phase)
            .forEach { addResolvedScopedScript(scripts, it, HttpScriptScope.GLOBAL, "全局") }
        HttpScopedScriptStore.loadProject(project, phase)
            .forEach { addResolvedScopedScript(scripts, it, HttpScriptScope.PROJECT, "项目") }
        val moduleName = draft.moduleName.trim()
        if (moduleName.isNotBlank()) {
            HttpScopedScriptStore.loadModule(project, moduleName, phase)
                .forEach { addResolvedScopedScript(scripts, it, HttpScriptScope.MODULE, "模块($moduleName)") }
        }
        val interfaceEnabled = when (phase) {
            HttpScriptPhase.PRE -> draft.preScriptEnabled
            HttpScriptPhase.POST -> draft.postScriptEnabled
        }
        val interfaceContent = when (phase) {
            HttpScriptPhase.PRE -> draft.preScript.orEmpty()
            HttpScriptPhase.POST -> draft.postScript.orEmpty()
        }.trim()
        if (interfaceEnabled && interfaceContent.isNotBlank()) {
            val defaultName = if (phase == HttpScriptPhase.PRE) "接口前置脚本" else "接口后置脚本"
            scripts.add(
                ResolvedScopedScript(
                    scope = HttpScriptScope.INTERFACE,
                    name = defaultName,
                    label = "接口/$defaultName",
                    content = interfaceContent
                )
            )
        }
        return scripts
    }

    private fun addResolvedScopedScript(
        target: MutableList<ResolvedScopedScript>,
        script: HttpScopedScriptEntry,
        scope: HttpScriptScope,
        scopeLabel: String
    ) {
        val content = script.content.trim()
        if (!script.enabled || content.isBlank()) {
            return
        }
        val name = script.name.trim().ifBlank { "未命名脚本" }
        target.add(
            ResolvedScopedScript(
                scope = scope,
                name = name,
                label = "$scopeLabel/$name",
                content = content
            )
        )
    }

    private fun executeScript(
        script: String,
        requestContext: HttpScriptRequestContext?,
        responseContext: HttpScriptResponseContext?,
        endpointContext: HttpScriptEndpointContext,
        vars: MutableMap<String, Any?>,
        logger: HttpScriptLogger
    ): Result<Unit> {
        val bindings = linkedMapOf<String, Any?>(
            "request" to requestContext,
            "response" to responseContext,
            "endpoint" to endpointContext,
            "env" to HttpScriptEnv(project),
            "vars" to vars,
            "store" to HttpScriptStore(project),
            "jvm" to HttpScriptJvmBridge(project, logger),
            "logger" to logger,
            "projectHash" to project.locationHash
        )
        val wrappedScript = buildString {
            appendLine("var log = function(message) { logger.info(message); };")
            appendLine("log.info = function(message) { logger.info(message); };")
            appendLine("log.debug = function(message) { logger.debug(message); };")
            appendLine("log.warn = function(message) { logger.warn(message); };")
            appendLine("log.error = function(message) { logger.error(message); };")
            appendLine("var debug = function(message) { logger.debug(message); };")
            appendLine("var warn = function(message) { logger.warn(message); };")
            appendLine("var error = function(message) { logger.error(message); };")
            appendLine(script)
        }
        return HttpScriptEngine.execute(wrappedScript, bindings, SCRIPT_TIMEOUT_MS)
    }

    private fun buildErrorSnapshot(
        statusText: String,
        start: Long,
        draft: HttpRequestDraft,
        body: String? = null
    ): HttpResponseSnapshot {
        return HttpResponseSnapshot(
            status = 0,
            statusText = statusText,
            durationMs = elapsedMs(start),
            sizeBytes = 0,
            requestMethod = draft.method,
            requestUrl = draft.url,
            requestParams = draft.params.toMutableList(),
            headers = mutableListOf(),
            body = body ?: statusText
        )
    }

    private fun toScriptEndpointContext(draft: HttpRequestDraft): HttpScriptEndpointContext {
        val meta = draft.codeMeta ?: return HttpScriptEndpointContext()
        val methodAnnotations = annotationsToScriptMap(meta.methodAnnotations)
        val parameters = linkedMapOf<String, HttpScriptEndpointParameterContext>()
        meta.parameters.forEachIndexed { index, parameter ->
            val name = parameter.name.trim().ifBlank { "arg$index" }
            parameters[name] = HttpScriptEndpointParameterContext(
                type = parameter.type,
                annotations = annotationsToScriptMap(parameter.annotations)
            )
        }
        val methodDescriptor = meta.methodDescriptor?.let { descriptor ->
            linkedMapOf<String, Any?>(
                "name" to descriptor.name,
                "declaringClass" to descriptor.declaringClass,
                "returnType" to descriptor.returnType,
                "parameterTypes" to descriptor.parameterTypes.toList(),
                "throwsTypes" to descriptor.throwsTypes.toList(),
                "modifiers" to descriptor.modifiers.toList()
            )
        }
        val classDescriptor = meta.classDescriptor?.let { descriptor ->
            linkedMapOf<String, Any?>(
                "name" to descriptor.name,
                "qualifiedName" to descriptor.qualifiedName,
                "superClass" to descriptor.superClass,
                "interfaces" to descriptor.interfaces.toList(),
                "modifiers" to descriptor.modifiers.toList(),
                "annotations" to annotationsToScriptMap(descriptor.annotations)
            )
        }
        return HttpScriptEndpointContext(
            source = meta.source,
            methodAnnotations = methodAnnotations,
            parameters = parameters,
            methodBody = meta.methodBody,
            methodDescriptor = methodDescriptor,
            classDescriptor = classDescriptor
        )
    }

    private fun annotationsToScriptMap(
        annotations: List<HttpScriptAnnotationMeta>
    ): MutableMap<String, MutableMap<String, String>> {
        val map = linkedMapOf<String, MutableMap<String, String>>()
        annotations.forEach { annotation ->
            val key = annotation.qualifiedName.trim()
            if (key.isBlank()) {
                return@forEach
            }
            val attrs = linkedMapOf<String, String>()
            annotation.attributes.forEach { attr ->
                val attrName = attr.key.trim()
                if (attrName.isNotBlank()) {
                    attrs[attrName] = attr.value
                }
            }
            map[key] = attrs
        }
        return map
    }

    private fun toScriptRequestContext(draft: HttpRequestDraft): HttpScriptRequestContext {
        val headers = entriesToMap(draft.headers)
        val cookies = linkedMapOf<String, String>()
        val explicitCookieHeader = removeHeaderIgnoreCase(headers, "Cookie")
        if (!explicitCookieHeader.isNullOrBlank()) {
            cookies.putAll(parseCookieHeader(explicitCookieHeader))
        }
        if (cookies.isEmpty()) {
            val persistedCookieHeader = buildCookieHeader(draft.url)
            if (!persistedCookieHeader.isNullOrBlank()) {
                cookies.putAll(parseCookieHeader(persistedCookieHeader))
            }
        }
        val formData = draft.formFields.map { field ->
            HttpScriptFormField(
                key = field.key,
                value = field.value,
                type = parseFormFieldType(field.fieldType).name
            )
        }.toMutableList()
        return HttpScriptRequestContext(
            method = draft.method,
            url = draft.url,
            timeoutSeconds = sanitizeTimeoutSeconds(draft.timeoutSeconds),
            pathParams = entriesToMap(draft.pathParams),
            params = entriesToMap(draft.params),
            headers = headers,
            cookies = cookies,
            bodyMode = parseBodyType(draft.bodyType).name,
            jsonBody = draft.body,
            urlEncoded = entriesToMap(draft.urlEncoded),
            formData = formData
        )
    }

    private fun fromScriptRequestContext(
        draft: HttpRequestDraft,
        context: HttpScriptRequestContext
    ): HttpRequestDraft {
        val bodyType = parseScriptBodyType(context.bodyMode)
        val headerMap = LinkedHashMap(context.headers)
        removeHeaderIgnoreCase(headerMap, "Cookie")
        val headers = mapToEntries(headerMap)
        val cookieHeader = buildCookieHeader(context.cookies)
        if (!cookieHeader.isNullOrBlank()) {
            headers.add(HttpKeyValue("Cookie", cookieHeader))
        }
        val timeout = if (context.timeoutSeconds > 0) {
            sanitizeTimeoutSeconds(context.timeoutSeconds)
        } else {
            sanitizeTimeoutSeconds(draft.timeoutSeconds)
        }
        val method = context.method.trim().uppercase().ifBlank { draft.method }
        val url = context.url.trim().ifBlank { draft.url }
        val body = if (bodyType == HttpBodyType.JSON) context.jsonBody else null
        val urlEncoded = if (bodyType == HttpBodyType.FORM_URLENCODED) {
            mapToEntries(context.urlEncoded)
        } else {
            mutableListOf()
        }
        val formData = if (bodyType == HttpBodyType.FORM_DATA) {
            context.formData.map { field ->
                HttpFormField(
                    key = field.key,
                    value = field.value,
                    fieldType = parseFormFieldType(field.type).name
                )
            }.toMutableList()
        } else {
            mutableListOf()
        }
        return draft.copy(
            method = method,
            url = url,
            timeoutSeconds = timeout,
            pathParams = mapToEntries(context.pathParams),
            params = mapToEntries(context.params),
            headers = headers,
            bodyType = bodyType.name,
            urlEncoded = urlEncoded,
            formFields = formData,
            body = body
        )
    }

    private fun toScriptResponseContext(
        snapshot: HttpResponseSnapshot,
        cookieMutations: List<CookieMutation>
    ): HttpScriptResponseContext {
        val cookies = cookieMutations.map { mutation ->
            HttpScriptCookie(
                name = mutation.entry.name,
                value = mutation.entry.value,
                domain = mutation.entry.domain,
                path = mutation.entry.path,
                expiresAt = mutation.entry.expiresAt,
                secure = mutation.entry.secure,
                httpOnly = mutation.entry.httpOnly,
                remove = mutation.remove
            )
        }.toMutableList()
        return HttpScriptResponseContext(
            status = snapshot.status,
            statusText = snapshot.statusText,
            headers = entriesToMap(snapshot.headers),
            body = snapshot.body,
            bodyBase64 = snapshot.bodyBase64,
            cookies = cookies
        )
    }

    private fun fromScriptResponseContext(
        snapshot: HttpResponseSnapshot,
        context: HttpScriptResponseContext
    ): HttpResponseSnapshot {
        val headers = mapToEntries(context.headers)
        val contentType = getHeaderIgnoreCase(context.headers, "Content-Type").orEmpty()
        val contentEncoding = getHeaderIgnoreCase(context.headers, "Content-Encoding").orEmpty()
        val body = context.body
        val bodyBase64 = if (!body.isNullOrBlank()) null else context.bodyBase64
        return snapshot.copy(
            status = context.status,
            statusText = context.statusText,
            sizeBytes = estimateBodySize(body, bodyBase64, snapshot.sizeBytes),
            contentType = contentType,
            contentEncoding = contentEncoding,
            encodingUnsupported = false,
            bodyTruncated = false,
            headers = headers,
            body = body,
            bodyBase64 = bodyBase64
        )
    }

    private fun toCookieMutations(
        context: HttpScriptResponseContext,
        requestUrl: String
    ): List<CookieMutation> {
        val uri = runCatching { URI(normalizeUrl(requestUrl)) }.getOrNull()
        val host = uri?.host.orEmpty()
        return context.cookies
            .filter { it.name.isNotBlank() }
            .map { cookie ->
                val entry = HttpCookieEntry(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain.ifBlank { host },
                    path = cookie.path.ifBlank { "/" },
                    expiresAt = cookie.expiresAt,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly
                )
                CookieMutation(entry, cookie.remove)
            }
    }

    private fun entriesToMap(entries: List<HttpKeyValue>): MutableMap<String, String> {
        val map = linkedMapOf<String, String>()
        entries.forEach { entry ->
            val key = entry.key.trim()
            if (key.isNotBlank()) {
                map[key] = entry.value
            }
        }
        return map
    }

    private fun mapToEntries(values: Map<String, String>): MutableList<HttpKeyValue> {
        return values.entries
            .mapNotNull { entry ->
                val key = entry.key.trim()
                if (key.isBlank()) null else HttpKeyValue(key, entry.value)
            }
            .toMutableList()
    }

    private fun removeHeaderIgnoreCase(headers: MutableMap<String, String>, name: String): String? {
        val key = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        return headers.remove(key)
    }

    private fun getHeaderIgnoreCase(headers: Map<String, String>, name: String): String? {
        val key = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        return headers[key]
    }

    private fun parseCookieHeader(value: String): MutableMap<String, String> {
        val cookies = linkedMapOf<String, String>()
        value.split(";").forEach { raw ->
            val part = raw.trim()
            if (part.isBlank()) {
                return@forEach
            }
            val index = part.indexOf('=')
            if (index <= 0) {
                return@forEach
            }
            val name = part.substring(0, index).trim()
            if (name.isBlank()) {
                return@forEach
            }
            val cookieValue = part.substring(index + 1).trim()
            cookies[name] = cookieValue
        }
        return cookies
    }

    private fun buildCookieHeader(cookies: Map<String, String>): String? {
        if (cookies.isEmpty()) {
            return null
        }
        val values = cookies.entries
            .filter { it.key.isNotBlank() }
            .map { "${it.key}=${it.value}" }
        if (values.isEmpty()) {
            return null
        }
        return values.joinToString("; ")
    }

    private fun estimateBodySize(body: String?, bodyBase64: String?, fallback: Long): Long {
        if (!body.isNullOrBlank()) {
            return body.toByteArray(StandardCharsets.UTF_8).size.toLong()
        }
        if (!bodyBase64.isNullOrBlank()) {
            val bytes = decodeBase64(bodyBase64)
            if (bytes != null) {
                return bytes.size.toLong()
            }
        }
        return fallback
    }

    private fun scriptErrorMessage(prefix: String, throwable: Throwable?): String {
        if (throwable == null) {
            return prefix
        }
        val message = throwable.message?.trim().orEmpty()
        return if (message.isBlank()) {
            prefix
        } else {
            "$prefix: $message"
        }
    }

    private fun updateResponse(response: HttpResponseSnapshot?) {
        currentResponse = response
        responseVersion++
        renderToken = null
        resetResponseRenderState()
        if (response == null) {
            responseSummary.text = "暂无响应"
            responseRawArea.text = ""
            responseRenderArea.text = ""
            responseRenderJsonArea.text = ""
            responseRenderXmlArea.text = ""
            responseRenderHtml.setHtml("")
            responseRenderImage.icon = null
            responseRenderInfo.text = ""
            setResponseDownloadEnabled(false)
            responseHeadersArea.text = ""
            responseRequestHeadersArea.text = ""
            responseRequestSummaryArea.text = ""
            return
        }
        val statusText = if (response.status > 0) {
            "状态 ${response.status}"
        } else {
            response.statusText.ifBlank { "错误" }
        }
        val duration = "${max(response.durationMs, 0)} ms"
        val size = formatBytes(response.sizeBytes)
        responseSummary.text = "$statusText | $duration | $size"
        renderTabIfNeeded(responseTabs.selectedIndex)
    }

    private fun updateRequestDocumentation(draft: HttpRequestDraft?) {
        if (draft == null) {
            requestDocExampleModeLabel.text = "请求体类型: 无"
            updateEditorTextSafely(requestDocBodyEditor, "{}")
            return
        }
        val bodyType = resolveRequestDocBodyType(draft)
        requestDocExampleModeLabel.text = "请求体类型: ${bodyTypeLabel(bodyType)}"
        updateEditorTextSafely(requestDocBodyEditor, buildRequestDocJsonExample(draft, bodyType))
    }

    private fun refreshRequestDocExampleFromUi() {
        if (isLoading) {
            return
        }
        val draft = buildDraftFromUI()
        updateRequestDocumentation(draft)
    }

    private fun bodyTypeLabel(type: HttpBodyType): String {
        return when (type) {
            HttpBodyType.NONE -> "无"
            HttpBodyType.JSON -> "JSON"
            HttpBodyType.FORM_URLENCODED -> "x-www-form-urlencoded"
            HttpBodyType.FORM_DATA -> "form-data"
        }
    }

    private fun resolveRequestDocBodyType(draft: HttpRequestDraft): HttpBodyType {
        var bodyType = parseBodyType(draft.bodyType)
        if (bodyType == HttpBodyType.NONE) {
            bodyType = when {
                draft.formFields.isNotEmpty() -> HttpBodyType.FORM_DATA
                draft.urlEncoded.isNotEmpty() -> HttpBodyType.FORM_URLENCODED
                draft.requestBodyParams.isNotEmpty() -> HttpBodyType.JSON
                !draft.body.isNullOrBlank() -> HttpBodyType.JSON
                else -> HttpBodyType.NONE
            }
        }
        return bodyType
    }

    private fun buildRequestDocJsonExample(draft: HttpRequestDraft, bodyType: HttpBodyType): String {
        return when (bodyType) {
            HttpBodyType.NONE -> "{}"
            HttpBodyType.JSON -> {
                val body = draft.body.orEmpty().trim()
                when {
                    body.isNotBlank() -> prettyJsonOrRaw(body)
                    draft.requestBodyParams.isNotEmpty() -> buildJsonExampleFromDocRows(draft.requestBodyParams)
                    else -> "{}"
                }
            }
            HttpBodyType.FORM_URLENCODED -> {
                val obj = linkedMapOf<String, Any?>()
                draft.urlEncoded.filter { it.key.isNotBlank() }.forEach { row ->
                    obj[row.key] = parseDocScalarValue(row.value)
                }
                runCatching { jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj) }
                    .getOrDefault("{}")
            }
            HttpBodyType.FORM_DATA -> {
                val obj = linkedMapOf<String, Any?>()
                draft.formFields.filter { it.key.isNotBlank() }.forEach { row ->
                    val type = parseFormFieldType(row.fieldType)
                    obj[row.key] = if (type == HttpFormFieldType.FILE) {
                        mapOf("type" to "FILE", "value" to row.value)
                    } else {
                        parseDocScalarValue(row.value)
                    }
                }
                runCatching { jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj) }
                    .getOrDefault("{}")
            }
        }
    }

    private fun prettyJsonOrRaw(value: String): String {
        val text = value.trim()
        if (text.isBlank()) {
            return text
        }
        return runCatching {
            jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonMapper.readTree(text))
        }.getOrDefault(text)
    }

    private fun buildJsonExampleFromDocRows(rows: List<HttpKeyValue>): String {
        if (rows.isEmpty()) {
            return "{}"
        }
        val root = linkedMapOf<String, Any?>()
        rows.filter { it.key.isNotBlank() }.forEach { row ->
            val segments = parseDocFieldSegments(row.key)
            if (segments.isEmpty()) {
                return@forEach
            }
            var current: Any? = root
            segments.forEachIndexed { index, segment ->
                val isLast = index == segments.lastIndex
                if (segment.isArray) {
                    val map = current as? MutableMap<String, Any?> ?: return@forEachIndexed
                    val list = map.getOrPut(segment.name) { mutableListOf<Any?>() } as? MutableList<Any?> ?: return@forEachIndexed
                    if (isLast) {
                        if (list.isEmpty()) {
                            list.add(parseDocScalarValue(row.value))
                        } else {
                            list[0] = parseDocScalarValue(row.value)
                        }
                    } else {
                        val next = if (list.isNotEmpty() && list[0] is MutableMap<*, *>) {
                            list[0] as MutableMap<String, Any?>
                        } else {
                            linkedMapOf<String, Any?>().also {
                                if (list.isEmpty()) {
                                    list.add(it)
                                } else {
                                    list[0] = it
                                }
                            }
                        }
                        current = next
                    }
                } else {
                    val map = current as? MutableMap<String, Any?> ?: return@forEachIndexed
                    if (isLast) {
                        map[segment.name] = parseDocScalarValue(row.value)
                    } else {
                        val next = map[segment.name] as? MutableMap<String, Any?> ?: linkedMapOf<String, Any?>().also {
                            map[segment.name] = it
                        }
                        current = next
                    }
                }
            }
        }
        return runCatching {
            jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
        }.getOrDefault(root.toString())
    }

    private fun parseDocScalarValue(raw: String): Any? {
        val text = raw.trim()
        if (text.isBlank()) {
            return ""
        }
        if (text.equals("true", ignoreCase = true)) {
            return true
        }
        if (text.equals("false", ignoreCase = true)) {
            return false
        }
        text.toIntOrNull()?.let { return it }
        text.toLongOrNull()?.let { return it }
        text.toDoubleOrNull()?.let { return it }
        return text
    }

    private fun syncRequestBodyFromDocEditor() {
        if (isLoading || syncingDocEditors) {
            return
        }
        val bodyType = selectedBodyType()
        if (bodyType != HttpBodyType.JSON) {
            return
        }
        val text = requestDocBodyEditor.text.trim()
        syncingDocEditors = true
        try {
            bodyArea.text = text
            val existingDescriptions = getTableEntries(requestDocParamsModel)
                .filter { it.key.isNotBlank() }
                .associate { it.key to it.description }
            val parsed = parseDocEntriesFromJson(text, existingDescriptions)
            if (parsed != null) {
                setTableEntries(requestDocParamsModel, parsed)
            }
        } finally {
            syncingDocEditors = false
        }
    }

    private fun updateEditorTextSafely(editor: MultiLanguageTextField, value: String) {
        if (syncingDocEditors) {
            return
        }
        val normalized = value.trim().ifBlank { "{}" }
        if (editor.text.trim() == normalized) {
            return
        }
        syncingDocEditors = true
        try {
            editor.text = normalized
        } finally {
            syncingDocEditors = false
        }
    }

    private fun syncResponseDocParamsFromBody() {
        if (isLoading) {
            return
        }
        val body = responseDocBodyEditor.text.trim()
        if (body.isBlank()) {
            setTableEntries(responseDocParamsModel, emptyList())
            return
        }
        val existingDescriptions = getTableEntries(responseDocParamsModel)
            .filter { it.key.isNotBlank() }
            .associate { it.key to it.description }
        val parsed = parseDocEntriesFromJson(body, existingDescriptions) ?: return
        setTableEntries(responseDocParamsModel, parsed)
    }

    private fun parseDocEntriesFromJson(
        text: String,
        descriptions: Map<String, String> = emptyMap()
    ): MutableList<HttpKeyValue>? {
        val root = runCatching { jsonMapper.readTree(text) }.getOrNull() ?: return null
        val rows = LinkedHashMap<String, HttpKeyValue>()
        collectDocEntriesFromJsonNode(root, "", rows)
        rows.values.forEach { row ->
            if (row.description.isBlank()) {
                row.description = descriptions[row.key].orEmpty()
            }
        }
        return rows.values.toMutableList()
    }

    private fun collectDocEntriesFromJsonNode(
        node: JsonNode?,
        path: String,
        rows: LinkedHashMap<String, HttpKeyValue>
    ) {
        if (node == null || node.isMissingNode) {
            return
        }
        when {
            node.isObject -> {
                if (path.isNotBlank()) {
                    rows.putIfAbsent(path, HttpKeyValue(key = path))
                }
                val iterator = node.fields()
                while (iterator.hasNext()) {
                    val field = iterator.next()
                    val childPath = if (path.isBlank()) field.key else "$path.${field.key}"
                    collectDocEntriesFromJsonNode(field.value, childPath, rows)
                }
            }
            node.isArray -> {
                val arrayPath = when {
                    path.isBlank() -> "items[]"
                    path.endsWith("[]") -> "$path.items[]"
                    else -> "$path[]"
                }
                if (node.size() == 0) {
                    rows.putIfAbsent(arrayPath, HttpKeyValue(key = arrayPath))
                    return
                }
                val first = node[0]
                if (first == null || first.isNull || first.isValueNode) {
                    rows[arrayPath] = HttpKeyValue(key = arrayPath, value = jsonNodeValueToDocValue(first))
                } else {
                    rows.putIfAbsent(arrayPath, HttpKeyValue(key = arrayPath))
                    collectDocEntriesFromJsonNode(first, arrayPath, rows)
                }
            }
            else -> {
                val key = if (path.isBlank()) "value" else path
                rows[key] = HttpKeyValue(key = key, value = jsonNodeValueToDocValue(node))
            }
        }
    }

    private fun jsonNodeValueToDocValue(node: JsonNode?): String {
        if (node == null || node.isNull) {
            return "null"
        }
        return when {
            node.isTextual -> node.textValue()
            node.isNumber || node.isBoolean -> node.asText()
            else -> node.toString()
        }
    }

    private fun renderTabIfNeeded(tabIndex: Int) {
        val response = currentResponse ?: return
        when (tabIndex) {
            RESPONSE_TAB_RAW_INDEX -> {
                if (rawRenderedVersion == responseVersion) {
                    return
                }
                val bodyText = response.body ?: ""
                val rawText = buildRawDisplay(response, bodyText)
                responseRawArea.text = rawText
                responseRawArea.setCaretPosition(0)
                rawRenderedVersion = responseVersion
            }
            RESPONSE_TAB_RENDER_INDEX -> {
                if (renderRenderedVersion == responseVersion || renderInFlightVersion == responseVersion) {
                    return
                }
                responseRenderInfo.text = "渲染中..."
                responseRenderLayout.show(responseRenderPanel, "binary")
                setResponseDownloadEnabled(false)
                renderResponseAsync()
            }
            RESPONSE_TAB_HEADERS_INDEX -> {
                if (headersRenderedVersion == responseVersion) {
                    return
                }
                responseHeadersArea.text = response.headers.joinToString("\n") { "${it.key}: ${it.value}" }
                responseHeadersArea.setCaretPosition(0)
                headersRenderedVersion = responseVersion
            }
            RESPONSE_TAB_REQUEST_HEADERS_INDEX -> {
                if (requestHeadersRenderedVersion == responseVersion) {
                    return
                }
                responseRequestHeadersArea.text = response.requestHeaders.joinToString("\n") { "${it.key}: ${it.value}" }
                responseRequestHeadersArea.setCaretPosition(0)
                requestHeadersRenderedVersion = responseVersion
            }
            RESPONSE_TAB_REQUEST_INFO_INDEX -> {
                if (requestInfoRenderedVersion == responseVersion) {
                    return
                }
                responseRequestSummaryArea.text = buildRequestSummary(response)
                responseRequestSummaryArea.setCaretPosition(0)
                requestInfoRenderedVersion = responseVersion
            }
        }
    }

    private fun resetResponseRenderState() {
        rawRenderedVersion = -1
        renderRenderedVersion = -1
        headersRenderedVersion = -1
        requestHeadersRenderedVersion = -1
        requestInfoRenderedVersion = -1
        renderInFlightVersion = -1
    }

    private fun createNewTab() {
        val draft = HttpRequestDraft(
            method = "GET",
            url = defaultUrl(),
            path = "/",
            timeoutSeconds = uiSettings.defaultTimeoutSeconds
        )
        val tab = HttpCallTab(
            id = nextTempTabId--,
            title = buildTabTitle(draft, null),
            draft = draft,
            sortIndex = callTabs.size
        )

        callTabs.add(tab)
        addCallTabUi(tab)
        selectCallTab(callTabs.size - 1)
        persistTabAsync(tab)
    }

    private fun ensureCurrentTab(): HttpCallTab {
        val tab = currentTab
        if (tab != null) {
            return tab
        }
        createNewTab()
        return currentTab ?: callTabs.first()
    }

    private fun buildHttpRequest(draft: HttpRequestDraft): HttpRequest {
        val uri = URI(normalizeUrl(draft.url))
        val timeoutSeconds = sanitizeTimeoutSeconds(draft.timeoutSeconds)
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
        val headers = draft.headers
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .toMutableList()
        val hasContentType = headers.any { it.key.equals("Content-Type", ignoreCase = true) }
        val bodyType = parseBodyType(draft.bodyType)
        val body = draft.body?.takeIf { it.isNotBlank() }
        val payload = buildPayload(bodyType, draft, body)
        if (payload.contentType != null && !hasContentType) {
            builder.header("Content-Type", payload.contentType)
        }
        applyDefaultHeaders(headers)
        headers.filterNot { isRestrictedHeader(it.key) }
            .forEach { builder.header(it.key, it.value) }
        if (!hasHeader(headers, "Cookie")) {
            val cookieHeader = buildCookieHeader(draft.url)
            if (!cookieHeader.isNullOrBlank()) {
                builder.header("Cookie", cookieHeader)
            }
        }
        val method = draft.method.uppercase()
        builder.method(method, payload.publisher)
        return builder.build()
    }

    private fun buildHttpClient(timeoutSeconds: Int): HttpClient {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .followRedirects(HttpClient.Redirect.NORMAL)
        applyProxySettings(builder, uiSettings)
        return builder.build()
    }

    private fun applyProxySettings(builder: HttpClient.Builder, settings: HttpUiSettings) {
        if (!settings.proxyEnabled) {
            return
        }
        val host = settings.proxyHost.trim()
        val port = settings.proxyPort
        if (host.isBlank() || port !in 1..65535) {
            return
        }
        val proxyType = normalizeProxyType(settings.proxyType)
        val selector = if (proxyType == "SOCKS") {
            val proxyAddress = InetSocketAddress.createUnresolved(host, port)
            val proxy = Proxy(Proxy.Type.SOCKS, proxyAddress)
            object : ProxySelector() {
                override fun select(uri: URI?): MutableList<Proxy> {
                    return mutableListOf(proxy)
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                }
            }
        } else {
            ProxySelector.of(InetSocketAddress.createUnresolved(host, port))
        }
        builder.proxy(selector)
        val username = settings.proxyUsername.trim()
        val password = settings.proxyPassword
        if (username.isNotBlank() && password.isNotEmpty()) {
            builder.authenticator(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestorType != RequestorType.PROXY) {
                        return null
                    }
                    return PasswordAuthentication(username, password.toCharArray())
                }
            })
        }
    }

    private fun normalizeProxyType(value: String?): String {
        return if (value?.trim()?.equals("SOCKS", ignoreCase = true) == true) "SOCKS" else "HTTP"
    }

    private fun applyDefaultHeaders(headers: MutableList<HttpKeyValue>) {
        val defaults = listOf(
            HttpKeyValue("Accept", "*/*"),
            HttpKeyValue("Accept-Encoding", "gzip, deflate"),
            HttpKeyValue("Accept-Language", "zh-CN,zh;q=0.9"),
            HttpKeyValue(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        )
        defaults.forEach { header ->
            if (!hasHeader(headers, header.key)) {
                headers.add(header)
            }
        }
    }

    private fun buildResponseSnapshot(
        response: HttpResponse<ByteArray>,
        start: Long
    ): HttpResponseSnapshot {
        val headers = response.headers().map().flatMap { (name, values) ->
            values.map { value -> HttpKeyValue(name, value) }
        }.toMutableList()
        val requestHeaders = response.request().headers().map().flatMap { (name, values) ->
            values.map { value -> HttpKeyValue(name, value) }
        }.toMutableList()
        val requestUrl = response.request().uri().toString()
        val requestMethod = response.request().method()
        val requestParams = parseQuery(response.request().uri().rawQuery)
        val rawBytes = response.body() ?: ByteArray(0)
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        val contentEncoding = response.headers().firstValue("Content-Encoding").orElse("")
        val (bytes, encodingUnsupported) = decodeContentEncoding(rawBytes, contentEncoding)
        val size = rawBytes.size.toLong()
        val charset = parseCharset(contentType) ?: StandardCharsets.UTF_8
        val attachment = isAttachment(headers)
        val isText = (contentType.isBlank() || isTextContent(contentType)) && !attachment && !encodingUnsupported
        val (bodyText, bodyBase64, truncated) = decodeBody(bytes, isText, charset)
        return HttpResponseSnapshot(
            status = response.statusCode(),
            statusText = "",
            durationMs = elapsedMs(start),
            sizeBytes = size,
            contentType = contentType,
            contentEncoding = contentEncoding,
            encodingUnsupported = encodingUnsupported,
            bodyTruncated = truncated,
            requestMethod = requestMethod,
            requestUrl = requestUrl,
            headers = headers,
            requestHeaders = requestHeaders,
            requestParams = requestParams,
            body = bodyText,
            bodyBase64 = bodyBase64
        )
    }

    private fun isTemplateAwareModel(model: DefaultTableModel): Boolean {
        return model === requestVarsModel ||
            model === pathParamsModel ||
            model === paramsModel ||
            model === headersModel ||
            model === urlEncodedModel ||
            model === formDataModel ||
            model === requestDocParamsModel ||
            model === responseDocParamsModel
    }

    private fun installTemplateAwareRenderer(table: JTable) {
        val delegate = table.getDefaultRenderer(Any::class.java)
        table.setDefaultRenderer(Any::class.java, TableCellRenderer { t, value, isSelected, hasFocus, row, column ->
            val targetTable = t ?: table
            val component = delegate.getTableCellRendererComponent(targetTable, value, isSelected, hasFocus, row, column)
            if (component is JComponent) {
                val modelColumn = targetTable.convertColumnIndexToModel(column)
                if (modelColumn == 1) {
                    applyTemplateCellDecoration(component, targetTable, value?.toString().orEmpty(), isSelected)
                } else {
                    resetTemplateCellDecoration(component, targetTable, isSelected)
                }
            }
            component
        })
    }

    private fun applyTemplateCellDecoration(component: JComponent, table: JTable, text: String, isSelected: Boolean) {
        val inspection = inspectTemplateText(text)
        if (!inspection.hasTemplate()) {
            resetTemplateCellDecoration(component, table, isSelected)
            return
        }
        component.toolTipText = buildTemplateInspectionTooltip(inspection)
        if (!isSelected) {
            component.foreground = if (inspection.hasUnresolved()) TEMPLATE_UNMATCHED_COLOR else TEMPLATE_MATCHED_COLOR
        }
    }

    private fun resetTemplateCellDecoration(component: JComponent, table: JTable, isSelected: Boolean) {
        component.toolTipText = null
        if (!isSelected) {
            component.foreground = table.foreground
        }
    }

    private fun updateUrlTemplateDecoration() {
        val inspection = inspectUrlText(urlField.text.trim())
        if (!inspection.hasTemplate()) {
            urlField.foreground = defaultUrlFieldForeground
            urlField.background = defaultUrlFieldBackground
            urlField.toolTipText = null
            urlField.putClientProperty("JComponent.outline", null)
            return
        }
        urlField.foreground = if (inspection.hasUnresolved()) TEMPLATE_UNMATCHED_COLOR else TEMPLATE_MATCHED_COLOR
        urlField.background = if (inspection.hasUnresolved()) URL_UNMATCHED_BACKGROUND else URL_MATCHED_BACKGROUND
        urlField.toolTipText = buildTemplateInspectionTooltip(inspection)
        urlField.putClientProperty("JComponent.outline", if (inspection.hasUnresolved()) "error" else null)
    }

    private fun inspectUrlText(text: String): TemplateInspectionResult {
        if (text.isBlank()) {
            return TemplateInspectionResult(emptyList())
        }
        val context = templatePreviewContext ?: buildTemplatePreviewContext().also { templatePreviewContext = it }
        val tokens = mutableListOf<TemplateTokenResult>()
        if (variableTemplateSettings.templateEnabled && text.contains("{{")) {
            TEMPLATE_EXPRESSION_REGEX.findAll(text).forEach { match ->
                val expression = match.groupValues.getOrElse(1) { "" }.trim()
                tokens.add(resolveTemplateToken(expression, context))
            }
        }
        if (text.contains('{')) {
            tokens.addAll(inspectPathPlaceholderTokens(text, context))
        }
        return TemplateInspectionResult(tokens)
    }

    private fun inspectTemplateText(text: String): TemplateInspectionResult {
        if (!variableTemplateSettings.templateEnabled || text.isBlank() || !text.contains("{{")) {
            return TemplateInspectionResult(emptyList())
        }
        val context = templatePreviewContext ?: buildTemplatePreviewContext().also { templatePreviewContext = it }
        val tokens = TEMPLATE_EXPRESSION_REGEX.findAll(text).map { match ->
            val expression = match.groupValues.getOrElse(1) { "" }.trim()
            resolveTemplateToken(expression, context)
        }.toList()
        return TemplateInspectionResult(tokens)
    }

    private fun inspectPathPlaceholderTokens(text: String, context: TemplatePreviewContext): List<TemplateTokenResult> {
        return URL_PATH_PLACEHOLDER_REGEX.findAll(text).map { match ->
            val key = match.groupValues.getOrElse(1) { "" }.trim()
            if (key.isBlank()) {
                TemplateTokenResult(
                    expression = "path",
                    value = null,
                    source = null,
                    reason = "路径变量名为空",
                    displayText = match.value
                )
            } else {
                val value = context.pathVars[key]
                if (value != null) {
                    TemplateTokenResult(
                        expression = "path.$key",
                        value = value,
                        source = "路径变量",
                        reason = null,
                        displayText = match.value
                    )
                } else {
                    TemplateTokenResult(
                        expression = "path.$key",
                        value = null,
                        source = null,
                        reason = "路径变量 `$key` 未匹配",
                        displayText = match.value
                    )
                }
            }
        }.toList()
    }

    private fun buildTemplatePreviewContext(): TemplatePreviewContext {
        val requestVars = getTableEntriesForPreview(requestVarsModel)
            .filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value }
        val pathVars = LinkedHashMap<String, String>()
        getTableEntriesForPreview(pathParamsModel)
            .filter { it.key.isNotBlank() }
            .forEach { row ->
                val key = row.key.trim()
                if (key.isNotBlank()) {
                    pathVars[key] = row.value
                    val unwrapped = key.removePrefix("{").removeSuffix("}").trim()
                    if (unwrapped.isNotBlank()) {
                        pathVars.putIfAbsent(unwrapped, row.value)
                    }
                }
            }
        return TemplatePreviewContext(
            requestVars = requestVars,
            pathVars = pathVars,
            projectEnv = HttpScriptEnvStore.loadProject(project),
            globalEnv = HttpScriptEnvStore.loadGlobal(),
            resolveOrder = variableTemplateSettings.resolveOrderEnum()
        )
    }

    private fun getTableEntriesForPreview(model: DefaultTableModel): List<HttpKeyValue> {
        val entries = mutableListOf<HttpKeyValue>()
        for (row in 0 until model.rowCount) {
            val key = readTableCellTextForPreview(model, row, 0)
            val value = readTableCellTextForPreview(model, row, 1)
            val description = if (model.columnCount > 2) {
                readTableCellTextForPreview(model, row, 2)
            } else {
                ""
            }
            if (key.isNotBlank() || value.isNotBlank()) {
                entries.add(HttpKeyValue(key, value, description))
            }
        }
        return entries
    }

    private fun readTableCellTextForPreview(model: DefaultTableModel, row: Int, column: Int): String {
        val table = tableByModel[model]
        if (table != null && table.model === model && table.isEditing) {
            val editingRow = table.convertRowIndexToModel(table.editingRow)
            val editingColumn = table.convertColumnIndexToModel(table.editingColumn)
            if (editingRow == row && editingColumn == column) {
                return table.cellEditor?.cellEditorValue?.toString()?.trim().orEmpty()
            }
        }
        return (model.getValueAt(row, column) as? String)?.trim().orEmpty()
    }

    private fun resolveTemplateToken(expression: String, context: TemplatePreviewContext): TemplateTokenResult {
        val expr = expression.trim()
        if (expr.isBlank()) {
            return TemplateTokenResult(expression = expression, value = null, source = null, reason = "变量表达式为空")
        }
        val unscoped = resolveUnscopedTemplate(expr, context)
        if (unscoped != null) {
            return TemplateTokenResult(expression = expression, value = unscoped.first, source = unscoped.second, reason = null)
        }
        val dot = expr.indexOf('.')
        if (dot <= 0 || dot >= expr.length - 1) {
            return TemplateTokenResult(expression = expression, value = null, source = null, reason = "未找到变量值")
        }
        val namespace = expr.substring(0, dot).trim().lowercase()
        val key = expr.substring(dot + 1).trim()
        if (key.isBlank()) {
            return TemplateTokenResult(expression = expression, value = null, source = null, reason = "变量名为空")
        }
        val resolved = when (namespace) {
            "env" -> context.projectEnv[key]?.let { it to "项目环境" } ?: context.globalEnv[key]?.let { it to "全局环境" }
            "project" -> context.projectEnv[key]?.let { it to "项目环境" }
            "global" -> context.globalEnv[key]?.let { it to "全局环境" }
            "api", "request", "var", "vars" -> context.requestVars[key]?.let { it to "接口变量" }
            "path" -> context.pathVars[key]?.let { it to "路径变量" }
            else -> {
                return TemplateTokenResult(
                    expression = expression,
                    value = null,
                    source = null,
                    reason = "未知命名空间 `$namespace`"
                )
            }
        }
        return if (resolved != null) {
            TemplateTokenResult(expression = expression, value = resolved.first, source = resolved.second, reason = null)
        } else {
            TemplateTokenResult(expression = expression, value = null, source = null, reason = "未找到变量值")
        }
    }

    private fun resolveUnscopedTemplate(expr: String, context: TemplatePreviewContext): Pair<String, String>? {
        return when (context.resolveOrder) {
            HttpVariableTemplateSettings.ResolveOrder.REQUEST_PROJECT_GLOBAL -> {
                context.requestVars[expr]?.let { it to "接口变量" }
                    ?: context.projectEnv[expr]?.let { it to "项目环境" }
                    ?: context.globalEnv[expr]?.let { it to "全局环境" }
            }
            HttpVariableTemplateSettings.ResolveOrder.PROJECT_GLOBAL_REQUEST -> {
                context.projectEnv[expr]?.let { it to "项目环境" }
                    ?: context.globalEnv[expr]?.let { it to "全局环境" }
                    ?: context.requestVars[expr]?.let { it to "接口变量" }
            }
        }
    }

    private fun buildTemplateInspectionTooltip(result: TemplateInspectionResult): String {
        val lines = result.tokens.map { token ->
            val expression = StringUtil.escapeXmlEntities(token.displayText)
            if (token.value != null) {
                val value = StringUtil.escapeXmlEntities(StringUtil.shortenTextWithEllipsis(token.value, 140, 0))
                val source = token.source?.let { "（$it）" }.orEmpty()
                "$expression -> $value$source"
            } else {
                val reason = StringUtil.escapeXmlEntities(token.reason ?: "未找到变量值")
                "$expression -> 未匹配：$reason"
            }
        }
        val orderText = when (variableTemplateSettings.resolveOrderEnum()) {
            HttpVariableTemplateSettings.ResolveOrder.PROJECT_GLOBAL_REQUEST -> "项目环境 > 全局环境 > 接口变量"
            HttpVariableTemplateSettings.ResolveOrder.REQUEST_PROJECT_GLOBAL -> "接口变量 > 项目环境 > 全局环境"
        }
        return "<html>${lines.joinToString("<br/>")}<br/><br/>默认优先级: ${StringUtil.escapeXmlEntities(orderText)}</html>"
    }

    private fun createKeyValuePanel(model: DefaultTableModel, emptyText: String): JPanel {
        val table = JBTable(model)
        table.emptyText.text = emptyText
        table.rowHeight = JBUI.scale(24)
        table.setShowGrid(false)
        if (isTemplateAwareModel(model)) {
            installTemplateAwareRenderer(table)
            templateAwareTables.add(table)
        }
        tableByModel[model] = table
        val addAction = simpleAction("添加", "添加一行", HttpIcons.add) {
            model.addRow(Array(model.columnCount) { "" })
            val row = model.rowCount - 1
            if (row >= 0) {
                table.editCellAt(row, 0)
            }
        }
        val removeAction = simpleAction("移除", "移除选中行", HttpIcons.remove) {
            val selected = table.selectedRows
            if (selected.isEmpty()) {
                if (model.rowCount > 0) {
                    model.removeRow(model.rowCount - 1)
                }
            } else {
                selected.sortedDescending().forEach { model.removeRow(it) }
            }
        }
        val toolbar = buildActionToolbar(
            "HttpKeyValueToolbar",
            listOf(addAction, removeAction),
            table
        )

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(toolbar.component, BorderLayout.NORTH)
        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        return panel
    }

    private fun createDocKeyValuePanel(model: DefaultTableModel, emptyText: String): JPanel {
        val root = DefaultMutableTreeNode(DocFieldTreeItem("root", "", false))
        val treeModel = DefaultTreeModel(root)
        val tree = Tree(treeModel)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = emptyText
        tree.rowHeight = JBUI.scale(22)
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ) {
                val node = value as? DefaultMutableTreeNode ?: return
                val item = node.userObject as? DocFieldTreeItem ?: return
                val nameText = if (item.isArray) "${item.name}[]" else item.name
                append(nameText, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                if (item.description.isNotBlank()) {
                    append("  | 说明: ${item.description}", SimpleTextAttributes.GRAY_ATTRIBUTES)
                }
                if (item.example.isNotBlank()) {
                    val shortened = StringUtil.shortenTextWithEllipsis(item.example.replace("\n", " "), 48, 0)
                    append("  | 示例: $shortened", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                }
                toolTipText = if (item.description.isBlank() && item.example.isBlank()) {
                    item.fullPath
                } else {
                    buildString {
                        append(item.fullPath)
                        if (item.description.isNotBlank()) {
                            append("\n说明: ").append(item.description)
                        }
                        if (item.example.isNotBlank()) {
                            append("\n示例: ").append(item.example)
                        }
                    }
                }
            }
        }
        fun selectedNode(): DefaultMutableTreeNode? {
            return tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
        }
        fun doAddField() {
            val parentNode = selectedNode()
            val parentPath = (parentNode?.userObject as? DocFieldTreeItem)?.fullPath.orEmpty()
            val initialPath = if (parentPath.isBlank()) "field" else "$parentPath.field"
            val dialog = DocFieldEditDialog(project, initialPath, "", "")
            if (!dialog.showAndGet()) {
                return
            }
            val path = dialog.fieldPath.trim()
            if (path.isBlank()) {
                return
            }
            upsertDocModelRow(model, path, dialog.fieldValue.trim(), dialog.fieldDescription.trim())
        }
        fun doEditField() {
            selectedNode()?.let { editDocTreeNode(model, it) }
        }
        fun doDeleteField() {
            val node = selectedNode() ?: return
            val item = node.userObject as? DocFieldTreeItem ?: return
            if (item.fullPath.isBlank()) {
                return
            }
            deleteDocTreeNode(model, item.fullPath)
        }
        fun doExpandAll() {
            expandAllTreeRows(tree)
        }
        fun doCollapseAll() {
            for (i in tree.rowCount - 1 downTo 1) {
                tree.collapseRow(i)
            }
        }
        val addAction = simpleAction("添加字段", "添加字段", HttpIcons.add) {
            doAddField()
        }
        val editAction = object : AnAction("编辑字段", "编辑选中字段", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                val node = selectedNode()
                if (node == null) {
                    Messages.showInfoMessage(project, "请先选中一个字段", "编辑字段")
                    return
                }
                editDocTreeNode(model, node)
            }

            override fun update(e: AnActionEvent) {
                val node = selectedNode()
                val item = node?.userObject as? DocFieldTreeItem
                e.presentation.isEnabled = item != null && item.fullPath.isNotBlank()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        val deleteAction = object : AnAction("删除字段", "删除选中字段", HttpIcons.remove) {
            override fun actionPerformed(e: AnActionEvent) {
                val node = selectedNode()
                val item = node?.userObject as? DocFieldTreeItem
                if (item == null || item.fullPath.isBlank()) {
                    Messages.showInfoMessage(project, "请先选中一个字段", "删除字段")
                    return
                }
                deleteDocTreeNode(model, item.fullPath)
            }

            override fun update(e: AnActionEvent) {
                val node = selectedNode()
                val item = node?.userObject as? DocFieldTreeItem
                e.presentation.isEnabled = item != null && item.fullPath.isNotBlank()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        val expandAction = simpleAction("展开", "展开全部", AllIcons.Actions.Expandall) {
            doExpandAll()
        }
        val collapseAction = simpleAction("收起", "收起全部", AllIcons.Actions.Collapseall) {
            doCollapseAll()
        }
        val toolbar = buildActionToolbar(
            "HttpDocTreeToolbar",
            listOf(addAction, editAction, deleteAction, expandAction, collapseAction),
            tree
        )
        toolbar.setReservePlaceAutoPopupIcon(false)
        toolbar.targetComponent = tree
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                maybeShowDocTreePopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowDocTreePopup(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    editDocTreeNode(model, node)
                }
            }

            private fun maybeShowDocTreePopup(e: MouseEvent) {
                if (!e.isPopupTrigger) {
                    return
                }
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                showDocTreePopup(model, node, tree, e.x, e.y)
            }
        })

        val refresh = {
            refreshDocFieldTree(model, treeModel, root, tree)
        }
        model.addTableModelListener {
            refresh()
        }
        refresh()

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(
            JBLabel("支持路径写法：data.id、items[].name；可右键/双击节点编辑，显示示例与说明。"),
            BorderLayout.NORTH
        )
        val content = JPanel(BorderLayout(0, 6))
        content.add(toolbar.component, BorderLayout.NORTH)
        content.add(JBScrollPane(tree), BorderLayout.CENTER)
        panel.add(content, BorderLayout.CENTER)
        return panel
    }

    private fun refreshDocFieldTree(
        model: DefaultTableModel,
        treeModel: DefaultTreeModel,
        root: DefaultMutableTreeNode,
        tree: Tree
    ) {
        root.removeAllChildren()
        val nodeIndex = LinkedHashMap<String, DefaultMutableTreeNode>()
        val rows = mutableListOf<Triple<String, String, String>>()
        for (row in 0 until model.rowCount) {
            val key = (model.getValueAt(row, 0) as? String)?.trim().orEmpty()
            if (key.isBlank()) {
                continue
            }
            val example = (model.getValueAt(row, 1) as? String)?.trim().orEmpty()
            val description = if (model.columnCount > 2) {
                (model.getValueAt(row, 2) as? String)?.trim().orEmpty()
            } else {
                ""
            }
            rows.add(Triple(key, example, description))
        }
        val rowKeys = rows.map { it.first }.toSet()
        rows.forEach { (key, example, description) ->
            val segments = parseDocFieldSegments(key)
            if (segments.isEmpty()) {
                return@forEach
            }
            var parent = root
            val pathBuilder = StringBuilder()
            segments.forEachIndexed { index, segment ->
                if (pathBuilder.isNotEmpty()) {
                    pathBuilder.append(".")
                }
                pathBuilder.append(segment.pathToken)
                val path = pathBuilder.toString()
                val node = nodeIndex[path] ?: DefaultMutableTreeNode(
                    DocFieldTreeItem(segment.name, path, segment.isArray)
                ).also {
                    parent.add(it)
                    nodeIndex[path] = it
                }
                val item = node.userObject as? DocFieldTreeItem
                if (item != null && index == segments.lastIndex) {
                    if (example.isNotBlank()) {
                        item.example = example
                    }
                    if (description.isNotBlank()) {
                        item.description = description
                    }
                    if (segment.isArray && node.childCount == 0 && !hasDocDescendantPath(rowKeys, path)) {
                        node.add(
                            DefaultMutableTreeNode(
                                DocFieldTreeItem(
                                    name = "items",
                                    fullPath = "",
                                    isArray = false,
                                    example = example,
                                    description = ""
                                )
                            )
                        )
                    }
                }
                parent = node
            }
        }
        treeModel.reload()
        expandAllTreeRows(tree)
    }

    private fun hasDocDescendantPath(keys: Set<String>, path: String): Boolean {
        if (path.isBlank()) {
            return false
        }
        val prefix = "$path."
        return keys.any { candidate ->
            val key = candidate.trim()
            key != path && key.startsWith(prefix)
        }
    }

    private fun showDocTreePopup(
        model: DefaultTableModel,
        node: DefaultMutableTreeNode,
        tree: Tree,
        x: Int,
        y: Int
    ) {
        val item = node.userObject as? DocFieldTreeItem ?: return
        if (item.fullPath.isBlank()) {
            return
        }
        val menu = JPopupMenu()
        val editItem = JMenuItem("编辑字段")
        editItem.addActionListener {
            editDocTreeNode(model, node)
        }
        val deleteItem = JMenuItem("删除字段")
        deleteItem.addActionListener {
            deleteDocTreeNode(model, item.fullPath)
        }
        menu.add(editItem)
        menu.add(deleteItem)
        menu.show(tree, x, y)
    }

    private fun editDocTreeNode(model: DefaultTableModel, node: DefaultMutableTreeNode) {
        val item = node.userObject as? DocFieldTreeItem ?: return
        val oldPath = item.fullPath.trim()
        if (oldPath.isBlank()) {
            return
        }
        val existing = getTableEntries(model).firstOrNull { it.key == oldPath }
        val dialog = DocFieldEditDialog(
            project = project,
            initialKey = oldPath,
            initialValue = existing?.value ?: item.example,
            initialDescription = existing?.description ?: item.description
        )
        if (!dialog.showAndGet()) {
            return
        }
        val newPath = dialog.fieldPath.trim()
        if (newPath.isBlank()) {
            Messages.showWarningDialog(project, "字段名不能为空", "编辑字段")
            return
        }
        val newValue = dialog.fieldValue.trim()
        val newDescription = dialog.fieldDescription.trim()

        if (newPath != oldPath) {
            renameDocFieldPrefix(model, oldPath, newPath)
        }
        upsertDocModelRow(model, newPath, newValue, newDescription)
    }

    private fun deleteDocTreeNode(model: DefaultTableModel, path: String) {
        val target = path.trim()
        if (target.isBlank()) {
            return
        }
        val confirm = Messages.showYesNoDialog(
            project,
            "确定删除字段 `$target` 及其子字段吗？",
            "删除字段",
            null
        )
        if (confirm != Messages.YES) {
            return
        }
        val filtered = getTableEntries(model)
            .filterNot { isDocPathMatchOrDescendant(it.key, target) }
        setTableEntries(model, filtered)
    }

    private fun renameDocFieldPrefix(model: DefaultTableModel, oldPrefix: String, newPrefix: String) {
        val renamed = getTableEntries(model).map { row ->
            if (isDocPathMatchOrDescendant(row.key, oldPrefix)) {
                row.copy(key = newPrefix + row.key.removePrefix(oldPrefix))
            } else {
                row.copy()
            }
        }
        setTableEntries(model, deduplicateDocEntries(renamed))
    }

    private fun upsertDocModelRow(
        model: DefaultTableModel,
        key: String,
        value: String,
        description: String
    ) {
        for (row in 0 until model.rowCount) {
            val existingKey = (model.getValueAt(row, 0) as? String)?.trim().orEmpty()
            if (existingKey == key) {
                model.setValueAt(value, row, 1)
                if (model.columnCount > 2) {
                    model.setValueAt(description, row, 2)
                }
                return
            }
        }
        if (model.columnCount > 2) {
            model.addRow(arrayOf(key, value, description))
        } else {
            model.addRow(arrayOf(key, value))
        }
    }

    private fun deduplicateDocEntries(entries: List<HttpKeyValue>): MutableList<HttpKeyValue> {
        val map = LinkedHashMap<String, HttpKeyValue>()
        entries.forEach { row ->
            val key = row.key.trim()
            if (key.isBlank()) {
                return@forEach
            }
            val existing = map[key]
            if (existing == null) {
                map[key] = row.copy(key = key)
            } else {
                if (row.value.isNotBlank()) {
                    existing.value = row.value
                }
                if (row.description.isNotBlank()) {
                    existing.description = row.description
                }
            }
        }
        return map.values.toMutableList()
    }

    private fun isDocPathMatchOrDescendant(candidate: String, parentPath: String): Boolean {
        val key = candidate.trim()
        val path = parentPath.trim()
        if (key.isBlank() || path.isBlank()) {
            return false
        }
        return key == path || key.startsWith("$path.")
    }

    private fun parseDocFieldSegments(path: String): List<DocFieldPathSegment> {
        val normalized = path.trim()
        if (normalized.isBlank()) {
            return emptyList()
        }
        val segments = mutableListOf<DocFieldPathSegment>()
        normalized.split(".").forEach { raw ->
            var part = raw.trim()
            if (part.isBlank()) {
                return@forEach
            }
            var arrayDepth = 0
            while (part.endsWith("[]")) {
                arrayDepth++
                part = part.removeSuffix("[]").trim()
            }
            if (part.isNotBlank()) {
                segments.add(
                    DocFieldPathSegment(
                        name = part,
                        isArray = arrayDepth > 0,
                        pathToken = if (arrayDepth > 0) "$part[]" else part
                    )
                )
            }
            if (arrayDepth > 1) {
                repeat(arrayDepth - 1) {
                    segments.add(
                        DocFieldPathSegment(
                            name = "items",
                            isArray = true,
                            pathToken = "items[]"
                        )
                    )
                }
            } else if (arrayDepth == 1 && part.isBlank()) {
                segments.add(
                    DocFieldPathSegment(
                        name = "items",
                        isArray = true,
                        pathToken = "items[]"
                    )
                )
            }
        }
        return segments
    }

    private fun expandAllTreeRows(tree: Tree) {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun createDocCardsContainer(cards: List<JComponent>): JComponent {
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        cards.forEachIndexed { index, card ->
            card.alignmentX = LEFT_ALIGNMENT
            content.add(card)
            if (index < cards.lastIndex) {
                content.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
        }
        val wrapper = JPanel(BorderLayout())
        wrapper.add(content, BorderLayout.NORTH)
        return JBScrollPane(wrapper)
    }

    private fun createCollapsibleCard(
        title: String,
        content: JComponent,
        collapsedInitially: Boolean
    ): JPanel {
        val contentPanel = JPanel(BorderLayout()).apply {
            add(content, BorderLayout.CENTER)
            isVisible = !collapsedInitially
        }
        val toggleButton = JButton(if (collapsedInitially) "展开" else "收起").apply {
            isFocusable = false
            margin = JBUI.insets(1, 8)
        }
        toggleButton.addActionListener {
            val willExpand = !contentPanel.isVisible
            contentPanel.isVisible = willExpand
            toggleButton.text = if (willExpand) "收起" else "展开"
            contentPanel.revalidate()
            contentPanel.repaint()
        }
        val header = JPanel(BorderLayout(6, 0))
        header.add(JBLabel(title), BorderLayout.WEST)
        header.add(toggleButton, BorderLayout.EAST)

        return JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1),
                JBUI.Borders.empty(8)
            )
            add(header, BorderLayout.NORTH)
            add(contentPanel, BorderLayout.CENTER)
        }
    }

    private fun createScriptPanel(
        editor: MultiLanguageTextField,
        enabledBox: JBCheckBox,
        phase: HttpScriptPhase
    ): JPanel {
        val phaseLabel = if (phase == HttpScriptPhase.PRE) "前置" else "后置"
        val baseHint = when (phase) {
            HttpScriptPhase.PRE -> "发送前依次执行：全局 -> 项目 -> 模块 -> 接口，可修改 request / vars。"
            HttpScriptPhase.POST -> "响应后依次执行：全局 -> 项目 -> 模块 -> 接口，可修改 response / vars / cookies。"
        }
        val hint = if (scriptFileType == PlainTextFileType.INSTANCE) {
            "$baseHint 当前 IDE 未检测到 JS 高亮能力，将以纯文本编辑。"
        } else {
            "$baseHint 可用对象：request / response / env / vars / store / jvm / log。"
        }
        val header = JPanel(BorderLayout(0, 4))
        val hintLabel = JBLabel(hint).apply {
            toolTipText = hint
        }
        header.add(hintLabel, BorderLayout.NORTH)

        val globalAction = object : AnAction("全局脚本", "管理全局${phaseLabel}脚本列表", HttpIcons.scriptScopeGlobal) {
            override fun actionPerformed(e: AnActionEvent) {
                ScopedScriptManageDialog(project, phase, HttpScriptScope.GLOBAL).show()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        val projectAction = object : AnAction("项目脚本", "管理项目${phaseLabel}脚本列表", HttpIcons.scriptScopeProject) {
            override fun actionPerformed(e: AnActionEvent) {
                ScopedScriptManageDialog(project, phase, HttpScriptScope.PROJECT).show()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        val moduleAction = object : AnAction("模块脚本", "管理模块${phaseLabel}脚本列表", HttpIcons.scriptScopeModule) {
            override fun actionPerformed(e: AnActionEvent) {
                val moduleName = currentTab?.draft?.moduleName?.trim().orEmpty()
                if (moduleName.isBlank()) {
                    Messages.showInfoMessage(
                        project,
                        "当前请求未绑定模块。\n从代码跳转创建请求后会自动关联模块。",
                        "模块脚本"
                    )
                    return
                }
                ScopedScriptManageDialog(project, phase, HttpScriptScope.MODULE, moduleName).show()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        val helpAction = object : AnAction("说明", "查看脚本 API 与返回约定", HttpIcons.scriptHelp) {
            override fun actionPerformed(e: AnActionEvent) {
                ScriptHelpDialog(project, phase).show()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        val templateAction = object : AnAction("示例", "插入接口脚本示例", HttpIcons.scriptTemplate) {
            override fun actionPerformed(e: AnActionEvent) {
                val template = if (phase == HttpScriptPhase.PRE) PRE_SCRIPT_TEMPLATE else POST_SCRIPT_TEMPLATE
                val current = editor.text.trim()
                val replace = current.isNotEmpty() &&
                    Messages.showYesNoDialog(
                        project,
                        "脚本编辑器已有内容，是否覆盖为示例？\n选择“否”将追加到末尾。",
                        "插入脚本示例",
                        null
                    ) == Messages.YES
                insertScriptText(editor, template, replace)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        val snippetAction = object : AnAction("片段", "插入接口脚本片段", HttpIcons.scriptSnippet) {
            override fun actionPerformed(e: AnActionEvent) {
                val snippets = if (phase == HttpScriptPhase.PRE) PRE_SCRIPT_SNIPPETS else POST_SCRIPT_SNIPPETS
                if (snippets.isEmpty()) {
                    return
                }
                val labels = snippets.map { it.title }.toTypedArray()
                val chooser = ScriptSnippetDialog(project, labels)
                if (!chooser.showAndGet()) {
                    return
                }
                val selected = chooser.selectedIndex
                if (selected < 0 || selected >= snippets.size) {
                    return
                }
                insertScriptText(editor, snippets[selected].content, false)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        val toolbar = buildActionToolbar(
            "HttpScriptToolbar.$phase",
            listOf(globalAction, projectAction, moduleAction, helpAction, templateAction, snippetAction),
            editor
        )
        val toolbarWrap = JPanel(BorderLayout())
        toolbarWrap.add(toolbar.component, BorderLayout.EAST)
        header.add(toolbarWrap, BorderLayout.SOUTH)

        val interfaceHeader = JPanel(BorderLayout(6, 0))
        interfaceHeader.add(enabledBox, BorderLayout.WEST)
        interfaceHeader.add(JBLabel("接口脚本 (单个)"), BorderLayout.EAST)
        val editorPanel = JPanel(BorderLayout(0, 6))
        editorPanel.add(interfaceHeader, BorderLayout.NORTH)
        editorPanel.add(editor, BorderLayout.CENTER)

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(header, BorderLayout.NORTH)
        panel.add(editorPanel, BorderLayout.CENTER)
        return panel
    }

    private fun resolveScriptFileType(): FileType {
        return runCatching {
            FileTypeManager.getInstance().getFileTypeByExtension("js")
        }.getOrNull()?.takeIf { it != PlainTextFileType.INSTANCE } ?: PlainTextFileType.INSTANCE
    }

    private fun insertScriptText(editor: MultiLanguageTextField, content: String, replace: Boolean) {
        if (content.isBlank()) {
            return
        }
        val document = editor.document
        val insertContent = if (replace || document.textLength == 0) {
            content
        } else {
            val prefix = if (document.text.endsWith("\n")) "" else "\n\n"
            prefix + content
        }
        WriteCommandAction.runWriteCommandAction(project) {
            if (replace || document.textLength == 0) {
                document.setText(insertContent)
            } else {
                document.insertString(document.textLength, insertContent)
            }
        }
    }

    private fun createViewerField(): EditorTextField {
        val document = EditorFactory.getInstance().createDocument("")
        val field = EditorTextField(document, project, PlainTextFileType.INSTANCE)
        field.setOneLineMode(false)
        field.setViewer(true)
        field.border = JBUI.Borders.empty()
        field.addSettingsProvider(EditorSettingsProvider { editor ->
            val settings = editor.settings
            settings.isUseSoftWraps = false
            settings.isLineNumbersShown = false
            settings.isLineMarkerAreaShown = false
            settings.isFoldingOutlineShown = false
            settings.isRightMarginShown = false
            settings.isCaretRowShown = false
            settings.additionalColumnsCount = 0
            settings.additionalLinesCount = 0
            editor.setVerticalScrollbarVisible(true)
            editor.setHorizontalScrollbarVisible(true)
            editor.scrollPane.border = JBUI.Borders.empty()
        })
        return field
    }

    private fun buildActionToolbar(
        place: String,
        actions: List<AnAction>,
        target: JComponent? = null
    ): ActionToolbar {
        val group = DefaultActionGroup()
        actions.forEach { group.add(it) }
        val toolbar = ActionManager.getInstance().createActionToolbar(place, group, true)
        toolbar.component.border = JBUI.Borders.empty(0, 2)
        if (target != null) {
            toolbar.targetComponent = target
        }
        return toolbar
    }

    private fun simpleAction(
        text: String,
        description: String,
        icon: Icon,
        handler: () -> Unit
    ): AnAction {
        return object : AnAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) {
                handler()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
    }

    private fun createCookiePanel(): JPanel {
        cookiesTable = JBTable(cookiesModel)
        cookiesTable.rowHeight = JBUI.scale(24)
        cookiesTable.setShowGrid(false)
        cookiesTable.emptyText.text = "暂无 Cookie"

        val booleanEditor = DefaultCellEditor(JComboBox(arrayOf("否", "是")))
        cookiesTable.columnModel.getColumn(5).cellEditor = booleanEditor
        cookiesTable.columnModel.getColumn(6).cellEditor = booleanEditor

        val addAction = simpleAction("添加", "添加 Cookie", HttpIcons.add) {
            cookiesModel.addRow(arrayOf("", "", "", "/", "", "否", "否"))
            val row = cookiesModel.rowCount - 1
            if (row >= 0) {
                cookiesTable.editCellAt(row, 0)
            }
        }
        val removeAction = simpleAction("移除", "移除选中 Cookie", HttpIcons.remove) {
            val selected = cookiesTable.selectedRows
            if (selected.isEmpty()) {
                if (cookiesModel.rowCount > 0) {
                    cookiesModel.removeRow(cookiesModel.rowCount - 1)
                }
            } else {
                selected.sortedDescending().forEach { cookiesModel.removeRow(it) }
            }
            persistCookiesFromTable()
        }
        val clearAction = simpleAction("清空", "清空 Cookie", HttpIcons.clear) {
            cookieEntries.clear()
            cookiesModel.setRowCount(0)
            HttpCookieStorage.clear(project)
        }

        cookiesModel.addTableModelListener {
            if (!isLoading) {
                persistCookiesFromTable()
            }
        }

        val toolbar = buildActionToolbar(
            "HttpCookieToolbar",
            listOf(addAction, removeAction, clearAction),
            cookiesTable
        )

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(toolbar.component, BorderLayout.NORTH)
        panel.add(JBScrollPane(cookiesTable), BorderLayout.CENTER)
        return panel
    }

    private fun getTableEntries(model: DefaultTableModel): MutableList<HttpKeyValue> {
        val entries = mutableListOf<HttpKeyValue>()
        for (row in 0 until model.rowCount) {
            val key = (model.getValueAt(row, 0) as? String)?.trim().orEmpty()
            val value = (model.getValueAt(row, 1) as? String)?.trim().orEmpty()
            val description = if (model.columnCount > 2) {
                (model.getValueAt(row, 2) as? String)?.trim().orEmpty()
            } else {
                ""
            }
            if (key.isNotBlank() || value.isNotBlank()) {
                entries.add(HttpKeyValue(key, value, description))
            }
        }
        return entries
    }

    private fun getResponseStatusDocs(): MutableList<HttpKeyValue> {
        val entries = mutableListOf<HttpKeyValue>()
        for (row in 0 until responseStatusDocsModel.rowCount) {
            val status = (responseStatusDocsModel.getValueAt(row, 0) as? String)?.trim().orEmpty()
            val description = (responseStatusDocsModel.getValueAt(row, 1) as? String)?.trim().orEmpty()
            if (status.isNotBlank() || description.isNotBlank()) {
                entries.add(HttpKeyValue(key = status, value = "", description = description))
            }
        }
        return entries
    }

    private fun setResponseStatusDocs(entries: List<HttpKeyValue>) {
        responseStatusDocsModel.setRowCount(0)
        entries.forEach { entry ->
            responseStatusDocsModel.addRow(arrayOf(entry.key, entry.description))
        }
    }

    private fun setTableEntries(model: DefaultTableModel, entries: List<HttpKeyValue>) {
        val normalizedEntries = if (model === requestDocParamsModel || model === responseDocParamsModel) {
            normalizeArrayDocEntries(entries)
        } else {
            entries
        }
        model.setRowCount(0)
        normalizedEntries.forEach { entry ->
            if (model.columnCount > 2) {
                model.addRow(arrayOf(entry.key, entry.value, entry.description))
            } else {
                model.addRow(arrayOf(entry.key, entry.value))
            }
        }
    }

    private fun normalizeArrayDocEntries(entries: List<HttpKeyValue>): List<HttpKeyValue> {
        val ordered = LinkedHashMap<String, HttpKeyValue>()
        entries.forEach { row ->
            val key = row.key.trim()
            if (key.isBlank()) {
                return@forEach
            }
            val normalized = row.copy(key = key)
            val existing = ordered[key]
            if (existing == null) {
                ordered[key] = normalized
            } else {
                if (existing.value.isBlank() && normalized.value.isNotBlank()) {
                    existing.value = normalized.value
                }
                if (existing.description.isBlank() && normalized.description.isNotBlank()) {
                    existing.description = normalized.description
                }
            }
        }
        val keys = ordered.keys.toSet()
        ordered.values.forEach { row ->
            val key = row.key
            if (!key.endsWith("[]")) {
                return@forEach
            }
            val baseKey = key.removeSuffix("[]")
            val baseRow = ordered[baseKey] ?: return@forEach
            if (row.value.isBlank() && baseRow.value.isNotBlank()) {
                row.value = baseRow.value
            }
            if (row.description.isBlank() && baseRow.description.isNotBlank()) {
                row.description = baseRow.description
            }
        }
        return ordered.values.filterNot { row ->
            !row.key.endsWith("[]") && keys.contains("${row.key}[]")
        }
    }

    private fun refreshCookieTable() {
        isLoading = true
        cookiesModel.setRowCount(0)
        cookieEntries.forEach { entry ->
            cookiesModel.addRow(
                arrayOf(
                    entry.name,
                    entry.value,
                    entry.domain,
                    entry.path,
                    formatCookieExpires(entry.expiresAt),
                    formatCookieBoolean(entry.secure),
                    formatCookieBoolean(entry.httpOnly)
                )
            )
        }
        isLoading = false
    }

    private fun persistCookiesFromTable() {
        if (isLoading) {
            return
        }
        val entries = getCookieEntriesFromTable()
        cookieEntries.clear()
        cookieEntries.addAll(entries)
        HttpCookieStorage.save(project, cookieEntries)
    }

    private fun getCookieEntriesFromTable(): MutableList<HttpCookieEntry> {
        val entries = mutableListOf<HttpCookieEntry>()
        for (row in 0 until cookiesModel.rowCount) {
            val name = (cookiesModel.getValueAt(row, 0) as? String)?.trim().orEmpty()
            val value = (cookiesModel.getValueAt(row, 1) as? String)?.trim().orEmpty()
            val domain = (cookiesModel.getValueAt(row, 2) as? String)?.trim().orEmpty()
            val path = (cookiesModel.getValueAt(row, 3) as? String)?.trim().orEmpty()
            val expiresAt = parseCookieExpires((cookiesModel.getValueAt(row, 4) as? String).orEmpty())
            val secure = parseCookieBoolean((cookiesModel.getValueAt(row, 5) as? String).orEmpty())
            val httpOnly = parseCookieBoolean((cookiesModel.getValueAt(row, 6) as? String).orEmpty())
            if (name.isNotBlank()) {
                entries.add(
                    HttpCookieEntry(
                        name = name,
                        value = value,
                        domain = domain,
                        path = path.ifBlank { "/" },
                        expiresAt = expiresAt,
                        secure = secure,
                        httpOnly = httpOnly
                    )
                )
            }
        }
        return entries
    }

    private fun formatCookieExpires(expiresAt: Long): String {
        if (expiresAt <= 0) {
            return ""
        }
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(expiresAt), ZoneId.systemDefault())
        return cookieDateFormatter.format(dateTime)
    }

    private fun parseCookieExpires(value: String): Long {
        val raw = value.trim()
        if (raw.isBlank()) {
            return 0
        }
        val number = raw.toLongOrNull()
        if (number != null) {
            return if (raw.length <= 10) number * 1000 else number
        }
        return try {
            val dateTime = LocalDateTime.parse(raw, cookieDateFormatter)
            dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0
        }
    }

    private fun formatCookieBoolean(value: Boolean): String {
        return if (value) "是" else "否"
    }

    private fun parseCookieBoolean(value: String): Boolean {
        return value.equals("是", ignoreCase = true) ||
            value.equals("true", ignoreCase = true) ||
            value.equals("1")
    }

    private fun extractCookieMutations(response: HttpResponse<ByteArray>): List<CookieMutation> {
        val headers = response.headers().allValues("Set-Cookie")
        if (headers.isEmpty()) {
            return emptyList()
        }
        val host = response.uri().host ?: ""
        val now = System.currentTimeMillis()
        val mutations = mutableListOf<CookieMutation>()
        headers.forEach { header ->
            val parsed = runCatching { HttpCookie.parse(header) }.getOrDefault(emptyList())
            parsed.forEach { cookie ->
                val name = cookie.name ?: return@forEach
                val value = cookie.value ?: ""
                val domain = cookie.domain?.ifBlank { host } ?: host
                val path = cookie.path?.ifBlank { "/" } ?: "/"
                val maxAge = cookie.maxAge
                if (maxAge == 0L) {
                    mutations.add(CookieMutation(HttpCookieEntry(name, value, domain, path), true))
                    return@forEach
                }
                val expiresAt = if (maxAge > 0) now + maxAge * 1000 else 0
                val entry = HttpCookieEntry(
                    name = name,
                    value = value,
                    domain = domain,
                    path = path,
                    expiresAt = expiresAt,
                    secure = cookie.secure,
                    httpOnly = cookie.isHttpOnly
                )
                mutations.add(CookieMutation(entry, false))
            }
        }
        return mutations
    }

    private fun applyCookieMutations(mutations: List<CookieMutation>) {
        val now = System.currentTimeMillis()
        mutations.forEach { mutation ->
            val key = cookieKey(mutation.entry)
            val index = cookieEntries.indexOfFirst { cookieKey(it) == key }
            if (mutation.remove || (mutation.entry.expiresAt > 0 && mutation.entry.expiresAt <= now)) {
                if (index >= 0) {
                    cookieEntries.removeAt(index)
                }
            } else if (index >= 0) {
                cookieEntries[index] = mutation.entry
            } else {
                cookieEntries.add(mutation.entry)
            }
        }
        HttpCookieStorage.save(project, cookieEntries)
        refreshCookieTable()
    }

    private fun buildCookieHeader(url: String): String? {
        val uri = runCatching { URI(normalizeUrl(url)) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val path = uri.path?.ifBlank { "/" } ?: "/"
        val scheme = uri.scheme ?: "http"
        val now = System.currentTimeMillis()
        val cookiesSnapshot = cookieEntries.toList()
        val cookies = cookiesSnapshot.filter { cookieMatches(it, host, path, scheme, now) }
        if (cookies.isEmpty()) {
            return null
        }
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun renderResponseAsync() {
        val response = currentResponse ?: return
        val version = responseVersion
        if (renderInFlightVersion == version) {
            return
        }
        renderInFlightVersion = version
        val token = UUID.randomUUID().toString()
        renderToken = token
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = prepareRenderResult(response)
            SwingUtilities.invokeLater {
                if (renderToken != token || responseVersion != version) {
                    return@invokeLater
                }
                applyRenderResult(result)
                renderRenderedVersion = version
                renderInFlightVersion = -1
            }
        }
    }

    private fun prepareRenderResult(response: HttpResponseSnapshot): RenderResult {
        val contentType = response.contentType
        val bytes = decodeBase64(response.bodyBase64)
        if (response.encodingUnsupported) {
            val encoding = response.contentEncoding.ifBlank { "unknown" }
            val info = "内容使用不支持的编码($encoding)，请保存文件或手动解码"
            return RenderResult(RenderKind.BINARY, null, null, null, info, bytes != null)
        }
        val trimmedType = contentType.substringBefore(';').trim().lowercase()
        return when {
            trimmedType.startsWith("image/") -> {
                if (bytes != null) {
                    RenderResult(RenderKind.IMAGE, null, null, bytes, "", true)
                } else {
                    RenderResult(RenderKind.BINARY, null, null, null, "图片内容不可用或过大", false)
                }
            }
            trimmedType.contains("html") -> {
                val body = response.body.orEmpty()
                val limit = uiSettings.maxRenderChars
                if ((limit > 0 && body.length > limit) || response.bodyTruncated) {
                    RenderResult(RenderKind.BINARY, null, null, null, "HTML 内容过大，无法渲染", bytes != null)
                } else {
                    RenderResult(RenderKind.HTML, null, body, null, "", false)
                }
            }
            trimmedType.contains("json") -> {
                RenderResult(RenderKind.JSON, response.body.orEmpty(), null, null, "", false)
            }
            trimmedType.contains("xml") -> {
                RenderResult(RenderKind.XML, response.body.orEmpty(), null, null, "", false)
            }
            else -> {
                val isBinary = trimmedType.isNotBlank() && !isTextContent(contentType)
                if (isAttachment(response) || isBinary) {
                    RenderResult(RenderKind.BINARY, null, null, null, buildBinaryInfo(response), bytes != null)
                } else {
                    RenderResult(RenderKind.TEXT, response.body.orEmpty(), null, null, "", false)
                }
            }
        }
    }

    private fun applyRenderResult(result: RenderResult) {
        responseRenderImage.icon = null
        responseRenderInfo.text = result.info
        setResponseDownloadEnabled(result.downloadable)
        val isTruncated = currentResponse?.bodyTruncated == true
        when (result.kind) {
            RenderKind.TEXT -> {
                responseRenderArea.text = result.text.orEmpty()
                responseRenderLayout.show(responseRenderPanel, "text")
                responseRenderArea.setCaretPosition(0)
            }
            RenderKind.JSON -> {
                if (isTruncated) {
                    responseRenderJsonArea.text = result.text.orEmpty()
                } else {
                    responseRenderJsonArea.setTextAndReformat(result.text.orEmpty())
                }
                responseRenderLayout.show(responseRenderPanel, "json")
                responseRenderJsonArea.editor?.caretModel?.moveToOffset(0)
                responseRenderJsonArea.editor?.scrollingModel?.scrollToCaret(ScrollType.MAKE_VISIBLE)
            }
            RenderKind.XML -> {
                if (isTruncated) {
                    responseRenderXmlArea.text = result.text.orEmpty()
                } else {
                    responseRenderXmlArea.setTextAndReformat(result.text.orEmpty())
                }
                responseRenderLayout.show(responseRenderPanel, "xml")
                responseRenderXmlArea.editor?.caretModel?.moveToOffset(0)
                responseRenderXmlArea.editor?.scrollingModel?.scrollToCaret(ScrollType.MAKE_VISIBLE)
            }
            RenderKind.HTML -> {
                responseRenderHtml.setHtml(result.html.orEmpty())
                responseRenderLayout.show(responseRenderPanel, "html")
                responseRenderHtml.caretPosition = 0
            }
            RenderKind.IMAGE -> {
                responseRenderImage.icon = result.imageBytes?.let { javax.swing.ImageIcon(it) }
                responseRenderLayout.show(responseRenderPanel, "image")
            }
            RenderKind.BINARY -> {
                responseRenderLayout.show(responseRenderPanel, "binary")
            }
        }
    }

    private fun buildRawFallback(response: HttpResponseSnapshot): String {
        if (response.encodingUnsupported) {
            val encoding = response.contentEncoding.ifBlank { "unknown" }
            val base64 = response.bodyBase64
            val header = "内容使用不支持的编码($encoding)"
            return if (base64.isNullOrBlank()) header else "$header\nBASE64:\n$base64"
        }
        if (response.bodyTruncated) {
            return "内容过大，已截断显示"
        }
        val base64 = response.bodyBase64
        return if (base64.isNullOrBlank()) "" else "BASE64:\n$base64"
    }

    private fun buildRawDisplay(response: HttpResponseSnapshot, bodyText: String): String {
        val raw = bodyText.ifBlank { buildRawFallback(response) }
        val limit = uiSettings.maxRawViewChars
        if (limit <= 0 || raw.length <= limit) {
            return raw
        }
        return raw.take(limit) + "\n\n[内容过大，仅显示前 $limit 字符]"
    }

    private fun buildRequestSummary(response: HttpResponseSnapshot): String {
        val lines = mutableListOf<String>()
        val method = response.requestMethod.ifBlank { currentTab?.draft?.method.orEmpty() }
        val url = response.requestUrl.ifBlank { currentTab?.draft?.url.orEmpty() }
        if (method.isNotBlank()) {
            lines.add("方法: $method")
        }
        if (url.isNotBlank()) {
            lines.add("地址: $url")
        }
        val fallbackParams = currentTab?.draft?.params ?: emptyList()
        val requestParams = when {
            response.requestParams.isNotEmpty() -> response.requestParams
            url.isNotBlank() -> {
                runCatching { parseQuery(URI(normalizeUrl(url)).rawQuery) }.getOrNull() ?: fallbackParams
            }
            else -> fallbackParams
        }.filter { it.key.isNotBlank() }
        if (requestParams.isEmpty()) {
            lines.add("参数: 无")
        } else {
            lines.add("参数:")
            requestParams.forEach { param ->
                lines.add("${param.key}=${param.value}")
            }
        }
        return lines.joinToString("\n")
    }

    private fun decodeBody(
        bytes: ByteArray,
        isText: Boolean,
        charset: java.nio.charset.Charset
    ): Triple<String?, String?, Boolean> {
        if (bytes.isEmpty()) {
            return Triple(null, null, false)
        }
        return if (isText) {
            val maxBytes = resolveTextLimitBytes()
            val truncated = maxBytes > 0 && bytes.size > maxBytes
            val slice = if (truncated && maxBytes > 0) bytes.copyOf(maxBytes) else bytes
            val text = String(slice, charset)
            Triple(text, null, truncated)
        } else {
            val truncated = bytes.size > MAX_BODY_BYTES
            val base64 = if (!truncated) Base64.getEncoder().encodeToString(bytes) else null
            Triple(null, base64, truncated)
        }
    }

    private fun decodeContentEncoding(
        bytes: ByteArray,
        encoding: String
    ): Pair<ByteArray, Boolean> {
        if (bytes.isEmpty() || encoding.isBlank()) {
            return bytes to false
        }
        val tokens = encoding.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return bytes to false
        }
        val unsupported = tokens.firstOrNull { token ->
            token != "gzip" && token != "x-gzip" && token != "deflate" && token != "identity"
        }
        if (unsupported != null) {
            return bytes to true
        }
        var current = bytes
        for (token in tokens.asReversed()) {
            when (token) {
                "gzip", "x-gzip" -> {
                    val decoded = runCatching { decodeGzip(current) }.getOrNull() ?: return bytes to true
                    current = decoded
                }
                "deflate" -> {
                    val decoded = runCatching { decodeDeflate(current) }.getOrNull() ?: return bytes to true
                    current = decoded
                }
                "identity" -> Unit
            }
        }
        return current to false
    }

    private fun decodeGzip(bytes: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
            return readAllBytes(stream)
        }
    }

    private fun decodeDeflate(bytes: ByteArray): ByteArray {
        val standard = runCatching {
            InflaterInputStream(ByteArrayInputStream(bytes)).use { stream ->
                readAllBytes(stream)
            }
        }.getOrNull()
        if (standard != null) {
            return standard
        }
        InflaterInputStream(ByteArrayInputStream(bytes), Inflater(true)).use { stream ->
            return readAllBytes(stream)
        }
    }

    private fun readAllBytes(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        stream.copyTo(output)
        return output.toByteArray()
    }

    private fun decodeBase64(value: String?): ByteArray? {
        if (value.isNullOrBlank()) {
            return null
        }
        return runCatching { Base64.getDecoder().decode(value) }.getOrNull()
    }

    private fun resolveTextLimitBytes(): Int {
        val limitChars = uiSettings.maxRawViewChars
        if (limitChars <= 0) {
            return 0
        }
        val approxBytes = limitChars.toLong() * 4
        return approxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun parseCharset(contentType: String): java.nio.charset.Charset? {
        val lower = contentType.lowercase()
        val index = lower.indexOf("charset=")
        if (index == -1) {
            return null
        }
        val charsetValue = lower.substring(index + 8).trim().trim('"', '\'').substringBefore(';')
        return runCatching { java.nio.charset.Charset.forName(charsetValue) }.getOrNull()
    }

    private fun isTextContent(contentType: String): Boolean {
        val lower = contentType.lowercase()
        return lower.startsWith("text/") ||
            lower.contains("json") ||
            lower.contains("xml") ||
            lower.contains("html") ||
            lower.contains("javascript")
    }

    private fun isAttachment(response: HttpResponseSnapshot): Boolean {
        return isAttachment(response.headers)
    }

    private fun isAttachment(headers: List<HttpKeyValue>): Boolean {
        val disposition = getHeaderValue(headers, "Content-Disposition") ?: return false
        val lower = disposition.lowercase()
        return lower.contains("attachment") || lower.contains("filename=")
    }

    private fun getHeaderValue(headers: List<HttpKeyValue>, name: String): String? {
        return headers.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun saveResponseToFile() {
        val response = currentResponse ?: return
        val bytes = decodeBase64(response.bodyBase64)
            ?: response.body?.toByteArray(StandardCharsets.UTF_8)
            ?: return
        val filename = extractFilename(response) ?: "response.bin"
        val descriptor = FileSaverDescriptor("保存响应", "选择保存位置")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val basePath = project.basePath?.let { Paths.get(it) }
        val wrapper = if (basePath != null) dialog.save(basePath, filename) else dialog.save(filename)
        val file = wrapper?.file ?: return
        Files.write(file.toPath(), bytes)
    }

    private fun extractFilename(response: HttpResponseSnapshot): String? {
        val disposition = getHeaderValue(response.headers, "Content-Disposition") ?: return null
        val regex = Regex("filename\\*?=\\\"?([^\\\";]+)\\\"?")
        val match = regex.find(disposition) ?: return null
        return match.groupValues.getOrNull(1)?.trim()
    }

    private fun buildBinaryInfo(response: HttpResponseSnapshot): String {
        val name = extractFilename(response)
        val size = formatBytes(response.sizeBytes)
        val truncated = if (response.bodyTruncated) " | 已截断" else ""
        val label = if (isAttachment(response)) "附件内容" else "二进制内容"
        return if (name.isNullOrBlank()) {
            "$label | $size$truncated"
        } else {
            "$label | $name | $size$truncated"
        }
    }

    private fun cookieMatches(
        entry: HttpCookieEntry,
        host: String,
        path: String,
        scheme: String,
        now: Long
    ): Boolean {
        if (host.isBlank()) {
            return false
        }
        if (entry.expiresAt > 0 && entry.expiresAt <= now) {
            return false
        }
        val domain = entry.domain.trimStart('.')
        if (domain.isNotBlank() && !host.endsWith(domain)) {
            return false
        }
        val cookiePath = entry.path.ifBlank { "/" }
        if (!path.startsWith(cookiePath)) {
            return false
        }
        if (entry.secure && scheme.lowercase() != "https") {
            return false
        }
        return true
    }

    private fun cookieKey(entry: HttpCookieEntry): String {
        val domain = entry.domain.trimStart('.')
        val path = entry.path.ifBlank { "/" }
        return "${entry.name}|$domain|$path"
    }

    private fun setSending(sending: Boolean) {
        isSending = sending
        requestActionsToolbar?.updateActionsImmediately()
    }

    private fun setResponseDownloadEnabled(enabled: Boolean) {
        responseDownloadEnabled = enabled
        responseActionsToolbar?.updateActionsImmediately()
    }

    private fun toggleResponsePanelCollapsed() {
        applyResponsePanelCollapsed(!responsePanelCollapsed, rememberCurrent = true)
    }

    private fun applyResponsePanelCollapsed(collapsed: Boolean, rememberCurrent: Boolean) {
        if (!::requestResponseSplit.isInitialized || !::responseTabs.isInitialized || !::responseCollapseButton.isInitialized) {
            return
        }
        if (collapsed) {
            if (rememberCurrent && !responsePanelCollapsed) {
                responseExpandedProportion = requestResponseSplit.proportion.coerceIn(0.35f, 0.92f)
            }
            responseTabs.isVisible = false
            requestResponseSplit.proportion = 0.995f
            responseCollapseButton.text = "展开响应"
            responseCollapseButton.toolTipText = "展开响应区域"
        } else {
            responseTabs.isVisible = true
            requestResponseSplit.proportion = responseExpandedProportion.coerceIn(0.35f, 0.92f)
            responseCollapseButton.text = "收起响应"
            responseCollapseButton.toolTipText = "收起响应区域"
        }
        responsePanelCollapsed = collapsed
        requestResponseSplit.revalidate()
        requestResponseSplit.repaint()
    }


    private fun formatBytes(bytes: Long): String {
        val size = max(bytes, 0)
        if (size < 1024) {
            return "$size B"
        }
        val kb = size / 1024.0
        if (kb < 1024) {
            return String.format("%.1f KB", kb)
        }
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }

    private fun buildCurlCommand(draft: HttpRequestDraft): String {
        val builder = StringBuilder()
        builder.append("curl -X ").append(draft.method)
        builder.append(" ").append(quoteCurl(draft.url))
        draft.headers.filter { it.key.isNotBlank() }.forEach { header ->
            builder.append(" -H ").append(quoteCurl("${header.key}: ${header.value}"))
        }
        val bodyType = parseBodyType(draft.bodyType)
        when (bodyType) {
            HttpBodyType.JSON -> {
                val body = draft.body?.trim().orEmpty()
                if (body.isNotBlank()) {
                    if (!hasHeader(draft.headers, "Content-Type")) {
                        builder.append(" -H ").append(quoteCurl("Content-Type: application/json"))
                    }
                    builder.append(" --data ").append(quoteCurl(body))
                }
            }
            HttpBodyType.FORM_URLENCODED -> {
                val body = buildUrlEncodedBody(draft.urlEncoded)
                if (body.isNotBlank()) {
                    if (!hasHeader(draft.headers, "Content-Type")) {
                        builder.append(" -H ").append(quoteCurl("Content-Type: application/x-www-form-urlencoded"))
                    }
                    builder.append(" --data ").append(quoteCurl(body))
                }
            }
            HttpBodyType.FORM_DATA -> {
                val fields = draft.formFields.filter { it.key.isNotBlank() && it.value.isNotBlank() }
                if (fields.isNotEmpty()) {
                    fields.forEach { field ->
                        val type = parseFormFieldType(field.fieldType)
                        val value = if (type == HttpFormFieldType.FILE) "@${field.value}" else field.value
                        builder.append(" -F ").append(quoteCurl("${field.key}=$value"))
                    }
                }
            }
            HttpBodyType.NONE -> {
                Unit
            }
        }
        return builder.toString()
    }

    private fun quoteCurl(value: String): String {
        val escaped = value.replace("'", "'\"'\"'")
        return "'$escaped'"
    }

    private fun normalizeUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        if (url.isBlank()) {
            return url
        }
        return if (url.contains("://")) url else "http://$url"
    }

    private fun parseUrl(url: String): UrlParts {
        if (url.isBlank()) {
            return UrlParts(url, "/", mutableListOf())
        }
        if (url.contains("{") || url.contains("}")) {
            return parseUrlLenient(url)
        }
        return try {
            val uri = URI(url)
            val baseUrl = URI(uri.scheme, uri.authority, uri.path, null, uri.fragment).toString()
            val path = uri.path?.ifBlank { "/" } ?: "/"
            val queryParams = parseQuery(uri.rawQuery)
            UrlParts(baseUrl, path, queryParams)
        } catch (_: Exception) {
            parseUrlLenient(url)
        }
    }

    private fun parseUrlLenient(url: String): UrlParts {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return UrlParts(trimmed, "/", mutableListOf())
        }
        val withoutFragment = trimmed.substringBefore("#")
        val queryIndex = withoutFragment.indexOf('?')
        val baseUrl = if (queryIndex >= 0) {
            withoutFragment.substring(0, queryIndex)
        } else {
            withoutFragment
        }
        val query = if (queryIndex >= 0 && queryIndex + 1 < withoutFragment.length) {
            withoutFragment.substring(queryIndex + 1)
        } else {
            null
        }
        val path = extractPathFromBaseUrl(baseUrl)
        val queryParams = parseQuery(query)
        return UrlParts(baseUrl, path, queryParams)
    }

    private fun extractPathFromBaseUrl(baseUrl: String): String {
        val schemeIndex = baseUrl.indexOf("://")
        val start = if (schemeIndex >= 0) schemeIndex + 3 else 0
        val pathIndex = baseUrl.indexOf('/', start)
        return if (pathIndex >= 0) {
            baseUrl.substring(pathIndex).ifBlank { "/" }
        } else {
            "/"
        }
    }

    private fun parseQuery(query: String?): MutableList<HttpKeyValue> {
        if (query.isNullOrBlank()) {
            return mutableListOf()
        }
        val items = mutableListOf<HttpKeyValue>()
        query.split("&").forEach { pair ->
            if (pair.isBlank()) {
                return@forEach
            }
            val parts = pair.split("=", limit = 2)
            val name = decode(parts[0])
            val value = if (parts.size > 1) decode(parts[1]) else ""
            items.add(HttpKeyValue(name, value))
        }
        return items
    }

    private fun mergeParams(
        fromUrl: List<HttpKeyValue>,
        fromTable: List<HttpKeyValue>
    ): MutableList<HttpKeyValue> {
        val merged = linkedMapOf<String, HttpKeyValue>()
        fromUrl.filter { it.key.isNotBlank() }.forEach { merged[it.key] = it.copy() }
        fromTable.filter { it.key.isNotBlank() }.forEach { merged[it.key] = it.copy() }
        return merged.values.toMutableList()
    }

    private fun buildUrl(baseUrl: String, params: List<HttpKeyValue>): String {
        if (params.isEmpty()) {
            return baseUrl
        }
        if (baseUrl.isBlank()) {
            return baseUrl
        }
        val query = params.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return "$baseUrl?$query"
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }

    private fun applyPathVariables(path: String, pathParams: List<HttpKeyValue>): String {
        if (path.isBlank()) {
            return "/"
        }
        val replacements = pathParams
            .filter { it.key.isNotBlank() }
            .associate { it.key to it.value }
        if (replacements.isEmpty()) {
            return path
        }
        return PATH_VARIABLE_REGEX.replace(path) { match ->
            val name = match.groupValues[1]
            val value = replacements[name]
            if (value.isNullOrBlank()) match.value else encodePathSegment(value)
        }
    }

    private fun replacePathInBaseUrl(baseUrl: String, oldPath: String, newPath: String): String {
        if (oldPath.isBlank()) {
            return baseUrl
        }
        if (baseUrl.endsWith(oldPath)) {
            return baseUrl.dropLast(oldPath.length) + newPath
        }
        if (oldPath == "/" && baseUrl.isNotBlank() && !baseUrl.endsWith("/")) {
            return baseUrl + newPath
        }
        return baseUrl
    }

    private fun encodePathSegment(value: String): String {
        return encode(value).replace("+", "%20")
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
    }

    private fun elapsedMs(start: Long): Long {
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun buildBodyPanel(): JPanel {
        val nonePanel = JPanel(BorderLayout())
        nonePanel.add(JBLabel("无请求体"), BorderLayout.CENTER)

        val jsonPanel = bodyArea
        val urlEncodedPanel = createKeyValuePanel(urlEncodedModel, "表单参数")
        val formDataPanel = createFormDataPanel()

        bodyCardPanel.add(nonePanel, HttpBodyType.NONE.name)
        bodyCardPanel.add(jsonPanel, HttpBodyType.JSON.name)
        bodyCardPanel.add(urlEncodedPanel, HttpBodyType.FORM_URLENCODED.name)
        bodyCardPanel.add(formDataPanel, HttpBodyType.FORM_DATA.name)

        bodyTypeBox.addActionListener { syncBodyCard() }
        syncBodyCard()

        val header = JPanel(BorderLayout(6, 0))
        header.add(JBLabel("类型"), BorderLayout.WEST)
        header.add(bodyTypeBox, BorderLayout.CENTER)

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(header, BorderLayout.NORTH)
        panel.add(bodyCardPanel, BorderLayout.CENTER)
        return panel
    }

    private fun buildApiDocPanel(): JPanel {
        val pathParamsDocPanel = createRequestMetaSummaryPanel(pathParamsModel, "暂无路径变量说明")
        val queryParamsDocPanel = createRequestMetaSummaryPanel(paramsModel, "暂无查询参数说明")
        val requestParamsPanel = createDocKeyValuePanel(requestDocParamsModel, "请求字段")
        val responseStatusPanel = createKeyValuePanel(responseStatusDocsModel, "响应状态码")
        val responseParamsPanel = createDocKeyValuePanel(responseDocParamsModel, "响应字段")

        val responseMeta = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 0.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 0, 2, 6)
        }
        responseMeta.add(JBLabel("响应状态"), c)
        c.gridx = 1
        c.weightx = 1.0
        responseMeta.add(responseDocStatusField, c)

        c.gridx = 0
        c.gridy++
        c.weightx = 0.0
        responseMeta.add(JBLabel("响应类型"), c)
        c.gridx = 1
        c.weightx = 1.0
        responseMeta.add(responseDocContentTypeField, c)

        c.gridx = 0
        c.gridy++
        c.weightx = 0.0
        responseMeta.add(JBLabel("响应说明"), c)
        c.gridx = 1
        c.weightx = 1.0
        responseMeta.add(responseDocDescriptionField, c)

        val requestExamplePanel = JPanel(BorderLayout(0, 6))
        requestExamplePanel.add(requestDocExampleModeLabel, BorderLayout.NORTH)
        requestExamplePanel.add(requestDocBodyEditor, BorderLayout.CENTER)

        val responseExamplePanel = JPanel(BorderLayout(0, 6))
        responseExamplePanel.add(responseDocBodyEditor, BorderLayout.CENTER)

        val requestDocContainer = createDocCardsContainer(
            listOf(
                createCollapsibleCard("路径变量说明（来自路径变量）", pathParamsDocPanel, collapsedInitially = false),
                createCollapsibleCard("查询参数说明（来自参数）", queryParamsDocPanel, collapsedInitially = false),
                createCollapsibleCard("请求参数说明（可编辑）", requestParamsPanel, collapsedInitially = false),
                createCollapsibleCard("请求示例（JSON 编辑器）", requestExamplePanel, collapsedInitially = false)
            )
        )
        val responseDocContainer = createDocCardsContainer(
            listOf(
                createCollapsibleCard("响应元信息（可编辑）", responseMeta, collapsedInitially = false),
                createCollapsibleCard("响应状态码说明（可编辑）", responseStatusPanel, collapsedInitially = false),
                createCollapsibleCard("响应参数说明（可编辑）", responseParamsPanel, collapsedInitially = false),
                createCollapsibleCard("响应示例（JSON 编辑器）", responseExamplePanel, collapsedInitially = false)
            )
        )

        val docTabs = JBTabbedPane()
        docTabs.addTab("请求文档", requestDocContainer)
        docTabs.addTab("响应文档", responseDocContainer)

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(JBLabel("说明：请求文档已包含路径变量、查询参数、请求字段；每个卡片可单独收起/展开。"), BorderLayout.NORTH)
        panel.add(docTabs, BorderLayout.CENTER)
        return panel
    }

    private fun createRequestMetaSummaryPanel(model: DefaultTableModel, emptyText: String): JPanel {
        val area = createViewerField()
        fun refresh() {
            val lines = mutableListOf<String>()
            for (row in 0 until model.rowCount) {
                val key = (model.getValueAt(row, 0) as? String)?.trim().orEmpty()
                val value = (model.getValueAt(row, 1) as? String)?.trim().orEmpty()
                val description = if (model.columnCount > 2) {
                    (model.getValueAt(row, 2) as? String)?.trim().orEmpty()
                } else {
                    ""
                }
                if (key.isBlank() && value.isBlank() && description.isBlank()) {
                    continue
                }
                val descriptionText = description.ifBlank { "无说明" }
                val exampleSuffix = value.takeIf { it.isNotBlank() }?.let { "；示例: $it" }.orEmpty()
                lines.add("- $key：$descriptionText$exampleSuffix")
            }
            area.text = if (lines.isEmpty()) {
                emptyText
            } else {
                lines.joinToString("\n")
            }
        }
        model.addTableModelListener { refresh() }
        refresh()
        return JPanel(BorderLayout()).apply {
            add(area, BorderLayout.CENTER)
        }
    }

    private fun createFormDataPanel(): JPanel {
        formDataTable = JBTable(formDataModel)
        formDataTable.rowHeight = JBUI.scale(24)
        formDataTable.setShowGrid(false)
        templateAwareTables.add(formDataTable)
        tableByModel[formDataModel] = formDataTable

        val typeColumn = formDataTable.columnModel.getColumn(2)
        val typeBox = JComboBox(arrayOf("文本", "文件"))
        typeColumn.cellEditor = DefaultCellEditor(typeBox)
        typeColumn.preferredWidth = JBUI.scale(80)

        val valueColumn = formDataTable.columnModel.getColumn(1)
        val defaultRenderer = formDataTable.getDefaultRenderer(Any::class.java)
        valueColumn.cellRenderer = TableCellRenderer { table, value, isSelected, hasFocus, row, column ->
            if (!isFileRow(row)) {
                val component = defaultRenderer.getTableCellRendererComponent(
                    table,
                    value,
                    isSelected,
                    hasFocus,
                    row,
                    column
                )
                if (component is JComponent) {
                    applyTemplateCellDecoration(component, table, value?.toString().orEmpty(), isSelected)
                }
                return@TableCellRenderer component
            }
            val label = JBLabel(fileButtonText(value as? String), HttpIcons.file, SwingConstants.LEFT)
            label.border = JBUI.Borders.empty(0, 4)
            label.toolTipText = (value as? String)?.trim().orEmpty()
            if (isSelected) {
                label.background = table.selectionBackground
                label.foreground = table.selectionForeground
                label.isOpaque = true
            } else {
                label.isOpaque = false
            }
            label
        }

        formDataTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = formDataTable.rowAtPoint(e.point)
                val column = formDataTable.columnAtPoint(e.point)
                if (row >= 0 && column == 1 && isFileRow(row)) {
                    chooseFileForRow(row)
                }
            }
        })

        formDataModel.addTableModelListener {
            formDataTable.repaint()
        }

        val addAction = simpleAction("添加", "添加表单项", HttpIcons.add) {
            formDataModel.addRow(arrayOf("", "", "文本"))
            val row = formDataModel.rowCount - 1
            if (row >= 0) {
                formDataTable.editCellAt(row, 0)
            }
        }
        val removeAction = simpleAction("移除", "移除选中项", HttpIcons.remove) {
            val selected = formDataTable.selectedRows
            if (selected.isEmpty()) {
                if (formDataModel.rowCount > 0) {
                    formDataModel.removeRow(formDataModel.rowCount - 1)
                }
            } else {
                selected.sortedDescending().forEach { formDataModel.removeRow(it) }
            }
        }

        val toolbar = buildActionToolbar(
            "HttpFormDataToolbar",
            listOf(addAction, removeAction),
            formDataTable
        )

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(toolbar.component, BorderLayout.NORTH)
        panel.add(JBScrollPane(formDataTable), BorderLayout.CENTER)
        return panel
    }

    private fun chooseFileForRow(row: Int) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
        val projectBase: VirtualFile? = null
        FileChooser.chooseFile(descriptor, project, projectBase) { file ->
            if (row < 0 || row >= formDataModel.rowCount) {
                return@chooseFile
            }
            formDataModel.setValueAt(file.path, row, 1)
            formDataModel.setValueAt("文件", row, 2)
        }
    }

    private fun syncBodyCard() {
        val bodyType = selectedBodyType().name
        bodyCardLayout.show(bodyCardPanel, bodyType)
    }

    private fun isFileRow(row: Int): Boolean {
        if (row < 0 || row >= formDataModel.rowCount) {
            return false
        }
        val typeLabel = (formDataModel.getValueAt(row, 2) as? String)?.trim().orEmpty()
        return parseFormFieldType(typeLabel) == HttpFormFieldType.FILE
    }

    private fun fileButtonText(path: String?): String {
        val raw = path?.trim().orEmpty()
        if (raw.isBlank()) {
            return "选择文件"
        }
        val name = runCatching { Paths.get(raw).fileName?.toString() }.getOrNull()
        return name?.ifBlank { "选择文件" } ?: "选择文件"
    }

    private fun selectedBodyType(): HttpBodyType {
        val option = bodyTypeBox.selectedItem as? BodyTypeOption
        return option?.type ?: HttpBodyType.NONE
    }

    private fun selectBodyType(type: HttpBodyType) {
        for (index in 0 until bodyTypeBox.itemCount) {
            val option = bodyTypeBox.getItemAt(index)
            if (option.type == type) {
                bodyTypeBox.selectedIndex = index
                break
            }
        }
        syncBodyCard()
    }

    private fun parseBodyType(value: String): HttpBodyType {
        return try {
            HttpBodyType.valueOf(value)
        } catch (_: Exception) {
            HttpBodyType.NONE
        }
    }

    private fun parseScriptBodyType(value: String): HttpBodyType {
        val normalized = value.trim().uppercase()
        return when (normalized) {
            "JSON" -> HttpBodyType.JSON
            "FORM_URLENCODED", "X-WWW-FORM-URLENCODED", "FORM-URLENCODED" -> HttpBodyType.FORM_URLENCODED
            "FORM_DATA", "FORM-DATA" -> HttpBodyType.FORM_DATA
            "NONE", "" -> HttpBodyType.NONE
            else -> parseBodyType(normalized)
        }
    }

    private fun resolveTimeoutSeconds(value: String): Int {
        val parsed = value.trim().toIntOrNull() ?: uiSettings.defaultTimeoutSeconds
        return sanitizeTimeoutSeconds(parsed)
    }

    private fun sanitizeTimeoutSeconds(value: Int): Int {
        return value.coerceIn(1, MAX_TIMEOUT_SECONDS)
    }

    private fun getFormFields(): MutableList<HttpFormField> {
        val fields = mutableListOf<HttpFormField>()
        for (row in 0 until formDataModel.rowCount) {
            val key = (formDataModel.getValueAt(row, 0) as? String)?.trim().orEmpty()
            val value = (formDataModel.getValueAt(row, 1) as? String)?.trim().orEmpty()
            val typeLabel = (formDataModel.getValueAt(row, 2) as? String)?.trim().orEmpty()
            if (key.isNotBlank() || value.isNotBlank()) {
                val fieldType = parseFormFieldType(typeLabel).name
                fields.add(HttpFormField(key, value, fieldType))
            }
        }
        return fields
    }

    private fun setFormFields(fields: List<HttpFormField>) {
        formDataModel.setRowCount(0)
        fields.forEach { field ->
            val label = if (parseFormFieldType(field.fieldType) == HttpFormFieldType.FILE) "文件" else "文本"
            formDataModel.addRow(arrayOf(field.key, field.value, label))
        }
    }

    private fun parseFormFieldType(value: String): HttpFormFieldType {
        return if (value.equals("文件", ignoreCase = true) || value.equals(HttpFormFieldType.FILE.name, true)) {
            HttpFormFieldType.FILE
        } else {
            HttpFormFieldType.TEXT
        }
    }

    private fun buildPayload(
        bodyType: HttpBodyType,
        draft: HttpRequestDraft,
        jsonBody: String?
    ): Payload {
        return when (bodyType) {
            HttpBodyType.NONE -> Payload(HttpRequest.BodyPublishers.noBody(), null)
            HttpBodyType.JSON -> {
                if (jsonBody.isNullOrBlank()) {
                    Payload(HttpRequest.BodyPublishers.noBody(), null)
                } else {
                    Payload(HttpRequest.BodyPublishers.ofString(jsonBody), "application/json")
                }
            }
            HttpBodyType.FORM_URLENCODED -> {
                val body = buildUrlEncodedBody(draft.urlEncoded)
                if (body.isBlank()) {
                    Payload(HttpRequest.BodyPublishers.noBody(), null)
                } else {
                    Payload(HttpRequest.BodyPublishers.ofString(body), "application/x-www-form-urlencoded")
                }
            }
            HttpBodyType.FORM_DATA -> {
                val multipart = buildMultipartBody(draft.formFields)
                if (multipart == null) {
                    Payload(HttpRequest.BodyPublishers.noBody(), null)
                } else {
                    Payload(multipart.publisher, multipart.contentType)
                }
            }
        }
    }

    private fun buildUrlEncodedBody(fields: List<HttpKeyValue>): String {
        val pairs = fields.filter { it.key.isNotBlank() }
        if (pairs.isEmpty()) {
            return ""
        }
        return pairs.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    }

    private fun buildMultipartBody(fields: List<HttpFormField>): MultipartPayload? {
        val normalized = fields.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        if (normalized.isEmpty()) {
            return null
        }
        val boundary = "jtools-${UUID.randomUUID()}"
        val output = ByteArrayOutputStream()
        val lineBreak = "\r\n"
        for (field in normalized) {
            val type = parseFormFieldType(field.fieldType)
            output.write("--$boundary$lineBreak".toByteArray(StandardCharsets.UTF_8))
            if (type == HttpFormFieldType.FILE) {
                val path = runCatching { Paths.get(field.value) }.getOrNull()
                val filename = path?.fileName?.toString() ?: "file"
                output.write(
                    "Content-Disposition: form-data; name=\"${escapeMultipart(field.key)}\"; filename=\"$filename\"$lineBreak".toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
                output.write("Content-Type: application/octet-stream$lineBreak$lineBreak".toByteArray(StandardCharsets.UTF_8))
                if (path != null && Files.exists(path)) {
                    output.write(Files.readAllBytes(path))
                } else {
                    output.write(field.value.toByteArray(StandardCharsets.UTF_8))
                }
                output.write(lineBreak.toByteArray(StandardCharsets.UTF_8))
            } else {
                output.write(
                    "Content-Disposition: form-data; name=\"${escapeMultipart(field.key)}\"$lineBreak$lineBreak".toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
                output.write(field.value.toByteArray(StandardCharsets.UTF_8))
                output.write(lineBreak.toByteArray(StandardCharsets.UTF_8))
            }
        }
        output.write("--$boundary--$lineBreak".toByteArray(StandardCharsets.UTF_8))
        return MultipartPayload(
            HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()),
            "multipart/form-data; boundary=$boundary"
        )
    }

    private fun escapeMultipart(value: String): String {
        return value.replace("\"", "\\\"")
    }

    private fun hasHeader(headers: List<HttpKeyValue>, name: String): Boolean {
        return headers.any { it.key.equals(name, ignoreCase = true) }
    }

    private fun isRestrictedHeader(name: String): Boolean {
        val normalized = name.trim().lowercase()
        return RESTRICTED_HEADERS.contains(normalized)
    }

    private fun defaultUrl(): String {
        val port = HttpPluginContext.getPort(project) ?: 8080
        return "http://localhost:$port/"
    }

    private data class PreScriptResult(
        val draft: HttpRequestDraft,
        val error: String? = null
    )

    private data class PostScriptResult(
        val snapshot: HttpResponseSnapshot,
        val cookieMutations: List<CookieMutation>
    )

    private data class UrlParts(
        val baseUrl: String,
        val path: String,
        val queryParams: MutableList<HttpKeyValue>
    )

    private data class Payload(
        val publisher: HttpRequest.BodyPublisher,
        val contentType: String?
    )

    private data class MultipartPayload(
        val publisher: HttpRequest.BodyPublisher,
        val contentType: String
    )

    private data class CookieMutation(val entry: HttpCookieEntry, val remove: Boolean)

    private enum class RenderKind {
        TEXT,
        JSON,
        XML,
        HTML,
        IMAGE,
        BINARY
    }

    private data class RenderResult(
        val kind: RenderKind,
        val text: String?,
        val html: String?,
        val imageBytes: ByteArray?,
        val info: String,
        val downloadable: Boolean
    )

    private inner class ApiTreeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode
            val obj = node?.userObject
            when (obj) {
                is HttpApiGroup -> {
                    icon = AllIcons.Nodes.Folder
                    append(obj.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    toolTipText = obj.name
                }
                is HttpSavedRequest -> {
                    icon = AllIcons.Actions.Execute
                    val name = obj.name.takeIf { it.isNotBlank() }
                    val method = obj.draft.method
                    val url = obj.draft.url
                    val title = name ?: "$method $url"
                    val display = StringUtil.shortenTextWithEllipsis(title, HISTORY_LABEL_LIMIT, 0)
                    append(display, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (name != null) {
                        val detail = StringUtil.shortenTextWithEllipsis("$method $url", HISTORY_LABEL_LIMIT, 0)
                        append("  $detail", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    toolTipText = title
                }
                else -> {
                    toolTipText = null
                }
            }
        }
    }

    private inner class ApiTreeTransferHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent): Int {
            return MOVE
        }

        override fun createTransferable(c: JComponent): Transferable? {
            val tree = c as? JTree ?: return null
            val path = tree.selectionPath ?: return null
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
            return TreeNodeTransferable(node)
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop) {
                return false
            }
            if (!support.isDataFlavorSupported(treeNodeFlavor)) {
                return false
            }
            val dropLocation = support.dropLocation as? JTree.DropLocation ?: return false
            val targetNode = dropLocation.path?.lastPathComponent as? DefaultMutableTreeNode ?: return false
            val targetObject = targetNode.userObject
            return targetObject is HttpApiGroup || targetNode === apiRootNode || targetObject is HttpSavedRequest
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) {
                return false
            }
            val dropLocation = support.dropLocation as? JTree.DropLocation ?: return false
            val targetNode = dropLocation.path?.lastPathComponent as? DefaultMutableTreeNode ?: return false
            val transferData = support.transferable.getTransferData(treeNodeFlavor) as? DefaultMutableTreeNode ?: return false
            val sourceObject = transferData.userObject
            val targetGroupId = when (val targetObj = targetNode.userObject) {
                is HttpApiGroup -> targetObj.id
                is HttpSavedRequest -> (targetNode.parent as? DefaultMutableTreeNode)?.userObject.let { (it as? HttpApiGroup)?.id }
                else -> null
            }
            when (sourceObject) {
                is HttpApiGroup -> {
                    if (isInvalidGroupMove(sourceObject.id, targetGroupId)) {
                        return false
                    }
                    sourceObject.parentId = targetGroupId
                    sourceObject.sortIndex = nextGroupSortIndex(targetGroupId)
                    HttpApiStorage.updateGroup(project, sourceObject)
                    rebuildApiTree()
                    selectGroupNode(sourceObject.id)
                    return true
                }
                is HttpSavedRequest -> {
                    sourceObject.groupId = targetGroupId
                    sourceObject.sortIndex = nextRequestSortIndex(targetGroupId)
                    HttpApiStorage.updateRequest(project, sourceObject)
                    rebuildApiTree()
                    selectRequestNode(sourceObject.id)
                    return true
                }
            }
            return false
        }

        private fun isInvalidGroupMove(groupId: Long, targetParentId: Long?): Boolean {
            if (targetParentId == null) {
                return false
            }
            if (groupId == targetParentId) {
                return true
            }
            val groupMap = apiGroups.associateBy { it.id }
            var currentId: Long? = targetParentId
            while (currentId != null) {
                if (currentId == groupId) {
                    return true
                }
                currentId = groupMap[currentId]?.parentId
            }
            return false
        }
    }

    private inner class TreeNodeTransferable(
        private val node: DefaultMutableTreeNode
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> {
            return arrayOf(treeNodeFlavor)
        }

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean {
            return treeNodeFlavor == flavor
        }

        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) {
                throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
            }
            return node
        }
    }

    private class ResponseHtmlPanel : HtmlPanel() {
        private var body: String? = null

        override fun getBody(): String {
            return body ?: ""
        }

        fun setHtml(value: String) {
            body = value
            setBody(value)
        }
    }

    private data class GroupOption(val id: Long?, val label: String) {
        override fun toString(): String {
            return label
        }
    }

    private inner class SaveRequestDialog(
        project: Project,
        request: HttpSavedRequest?,
        options: List<GroupOption>
    ) : DialogWrapper(project) {
        private val nameField = JBTextField(request?.name ?: "")
        private val groupBox = JComboBox(options.toTypedArray())

        val requestName: String
            get() = nameField.text

        val groupId: Long?
            get() = (groupBox.selectedItem as? GroupOption)?.id

        init {
            title = if (request == null) "保存接口" else "更新接口"
            val selected = options.firstOrNull { it.id == request?.groupId }
            if (selected != null) {
                groupBox.selectedItem = selected
            }
            init()
        }

        override fun createCenterPanel(): JPanel {
            val panel = JPanel(GridBagLayout())
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                gridx = 0
                gridy = 0
                insets = JBUI.insets(4)
            }

            panel.add(JBLabel("名称"), c)
            c.gridy++
            panel.add(nameField, c)

            c.gridy++
            panel.add(JBLabel("分组"), c)
            c.gridy++
            panel.add(groupBox, c)

            return panel
        }
    }

    private inner class SettingsDialog(
        project: Project,
        settings: HttpUiSettings
    ) : DialogWrapper(project) {
        private val timeoutField = JBTextField(settings.defaultTimeoutSeconds.toString())
        private val rawLimitField = JBTextField(settings.maxRawViewChars.toString())
        private val renderLimitField = JBTextField(settings.maxRenderChars.toString())
        private val proxyEnabledBox = JBCheckBox("启用代理", settings.proxyEnabled)
        private val proxyTypeBox = JComboBox(PROXY_TYPES)
        private val proxyHostField = JBTextField(settings.proxyHost)
        private val proxyPortField = JBTextField(if (settings.proxyPort in 1..65535) settings.proxyPort.toString() else "")
        private val proxyUsernameField = JBTextField(settings.proxyUsername)
        private val proxyPasswordField = JBPasswordField().apply {
            text = settings.proxyPassword
        }
        private val lineMarkerEnabledBox = JBCheckBox("显示可调用图标", settings.lineMarkerEnabled)
        private val contextMenuEnabledBox = JBCheckBox("显示右键添加菜单", settings.contextMenuEnabled)

        init {
            title = "设置"
            proxyTypeBox.selectedItem = normalizeProxyType(settings.proxyType)
            proxyEnabledBox.addActionListener { updateProxyFieldsEnabled() }
            updateProxyFieldsEnabled()
            init()
        }

        override fun createCenterPanel(): JPanel {
            val panel = JPanel(GridBagLayout())
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                gridx = 0
                gridy = 0
                insets = JBUI.insets(4)
            }

            panel.add(JBLabel("默认超时(s)"), c)
            c.gridy++
            panel.add(timeoutField, c)

            c.gridy++
            panel.add(JBLabel("原始预览上限(字符, 0=不限)"), c)
            c.gridy++
            panel.add(rawLimitField, c)

            c.gridy++
            panel.add(JBLabel("渲染预览上限(字符, 0=不限)"), c)
            c.gridy++
            panel.add(renderLimitField, c)

            c.gridy++
            panel.add(lineMarkerEnabledBox, c)

            c.gridy++
            panel.add(contextMenuEnabledBox, c)

            c.gridy++
            panel.add(JSeparator(), c)

            c.gridy++
            panel.add(JBLabel("命名空间: {{env.key}} / {{project.key}} / {{global.key}} / {{api.key}} / {{path.id}}"), c)

            c.gridy++
            panel.add(JButton("变量使用说明").apply {
                addActionListener { showVariableHelpDialog() }
            }, c)

            c.gridy++
            panel.add(JSeparator(), c)

            c.gridy++
            panel.add(proxyEnabledBox, c)

            c.gridy++
            panel.add(JBLabel("代理类型"), c)
            c.gridy++
            panel.add(proxyTypeBox, c)

            c.gridy++
            panel.add(JBLabel("代理地址"), c)
            c.gridy++
            panel.add(proxyHostField, c)

            c.gridy++
            panel.add(JBLabel("代理端口"), c)
            c.gridy++
            panel.add(proxyPortField, c)

            c.gridy++
            panel.add(JBLabel("代理用户名(可选)"), c)
            c.gridy++
            panel.add(proxyUsernameField, c)

            c.gridy++
            panel.add(JBLabel("代理密码(可选)"), c)
            c.gridy++
            panel.add(proxyPasswordField, c)

            panel.preferredSize = JBUI.size(350, 0)
            return panel
        }

        override fun doValidate(): ValidationInfo? {
            val timeout = timeoutField.text.trim().toIntOrNull()
                ?: return ValidationInfo("请输入数字", timeoutField)
            if (timeout !in 1..MAX_TIMEOUT_SECONDS) {
                return ValidationInfo("超时范围 1-$MAX_TIMEOUT_SECONDS", timeoutField)
            }
            val rawLimit = rawLimitField.text.trim().toIntOrNull()
                ?: return ValidationInfo("请输入数字", rawLimitField)
            if (rawLimit != 0 && rawLimit !in MIN_PREVIEW_CHARS..MAX_PREVIEW_CHARS) {
                return ValidationInfo("范围 0(不限) 或 $MIN_PREVIEW_CHARS-$MAX_PREVIEW_CHARS", rawLimitField)
            }
            val renderLimit = renderLimitField.text.trim().toIntOrNull()
                ?: return ValidationInfo("请输入数字", renderLimitField)
            if (renderLimit != 0 && renderLimit !in MIN_PREVIEW_CHARS..MAX_PREVIEW_CHARS) {
                return ValidationInfo("范围 0(不限) 或 $MIN_PREVIEW_CHARS-$MAX_PREVIEW_CHARS", renderLimitField)
            }
            if (proxyEnabledBox.isSelected) {
                if (proxyHostField.text.trim().isBlank()) {
                    return ValidationInfo("启用代理后，代理地址不能为空", proxyHostField)
                }
                val port = proxyPortField.text.trim().toIntOrNull()
                    ?: return ValidationInfo("代理端口必须是数字", proxyPortField)
                if (port !in 1..65535) {
                    return ValidationInfo("代理端口范围 1-65535", proxyPortField)
                }
                val username = proxyUsernameField.text.trim()
                val password = String(proxyPasswordField.password)
                if ((username.isBlank() && password.isNotBlank()) || (username.isNotBlank() && password.isBlank())) {
                    return ValidationInfo("代理认证需要同时填写用户名和密码，或全部留空", proxyUsernameField)
                }
            }
            return null
        }

        fun toSettings(): HttpUiSettings {
            return HttpUiSettings(
                defaultTimeoutSeconds = timeoutField.text.trim().toIntOrNull() ?: uiSettings.defaultTimeoutSeconds,
                maxRawViewChars = rawLimitField.text.trim().toIntOrNull() ?: uiSettings.maxRawViewChars,
                maxRenderChars = renderLimitField.text.trim().toIntOrNull() ?: uiSettings.maxRenderChars,
                lineMarkerEnabled = lineMarkerEnabledBox.isSelected,
                contextMenuEnabled = contextMenuEnabledBox.isSelected,
                proxyEnabled = proxyEnabledBox.isSelected,
                proxyType = normalizeProxyType(proxyTypeBox.selectedItem?.toString()),
                proxyHost = proxyHostField.text.trim(),
                proxyPort = proxyPortField.text.trim().toIntOrNull() ?: 0,
                proxyUsername = proxyUsernameField.text.trim(),
                proxyPassword = String(proxyPasswordField.password)
            )
        }

        fun toVariableTemplateSettings(): HttpVariableTemplateSettings {
            return HttpVariableTemplateSettings(
                templateEnabled = true,
                unresolvedPolicy = HttpVariableTemplateSettings.UnresolvedPolicy.KEEP.name,
                unscopedResolveOrder = HttpVariableTemplateSettings.ResolveOrder.REQUEST_PROJECT_GLOBAL.name
            )
        }

        private fun showVariableHelpDialog() {
            object : DialogWrapper(this@HttpClientPanel.project) {
                init {
                    title = "变量使用说明"
                    init()
                }

                override fun createCenterPanel(): JComponent {
                    val pane = JTextPane().apply {
                        contentType = "text/html"
                        isEditable = false
                        isOpaque = false
                        text = VARIABLE_TEMPLATE_HELP_HTML
                        caretPosition = 0
                    }
                    return JPanel(BorderLayout()).apply {
                        preferredSize = JBUI.size(720, 520)
                        add(JBScrollPane(pane), BorderLayout.CENTER)
                    }
                }
            }.show()
        }

        private fun updateProxyFieldsEnabled() {
            val enabled = proxyEnabledBox.isSelected
            proxyTypeBox.isEnabled = enabled
            proxyHostField.isEnabled = enabled
            proxyPortField.isEnabled = enabled
            proxyUsernameField.isEnabled = enabled
            proxyPasswordField.isEnabled = enabled
        }

    }

    private inner class ScriptEnvDialog(
        project: Project
    ) : DialogWrapper(project) {
        private val projectModel = DefaultTableModel(arrayOf("键", "值"), 0)
        private val globalModel = DefaultTableModel(arrayOf("键", "值"), 0)

        init {
            title = "环境变量"
            loadModel(projectModel, HttpScriptEnvStore.loadProject(project))
            loadModel(globalModel, HttpScriptEnvStore.loadGlobal())
            init()
        }

        override fun createCenterPanel(): JComponent {
            val tabs = JBTabbedPane()
            tabs.addTab("项目环境", createEnvPanel(projectModel))
            tabs.addTab("全局环境", createEnvPanel(globalModel))
            return JPanel(BorderLayout(0, 8)).apply {
                preferredSize = JBUI.size(680, 460)
                add(JBLabel("读取优先级：项目环境 > 全局环境"), BorderLayout.NORTH)
                add(tabs, BorderLayout.CENTER)
            }
        }

        override fun doOKAction() {
            val projectValues = readModel(projectModel)
            val globalValues = readModel(globalModel)
            HttpScriptEnvStore.saveProject(project, projectValues)
            HttpScriptEnvStore.saveGlobal(globalValues)
            invalidateTemplatePreviewContext()
            scheduleTemplateDecorationsRefresh()
            super.doOKAction()
        }

        private fun createEnvPanel(model: DefaultTableModel): JPanel {
            val table = JBTable(model)
            table.rowHeight = JBUI.scale(24)
            table.setShowGrid(false)
            table.emptyText.text = "暂无变量"

            val addAction = simpleAction("添加", "添加变量", HttpIcons.add) {
                model.addRow(arrayOf("", ""))
                val row = model.rowCount - 1
                if (row >= 0) {
                    table.editCellAt(row, 0)
                }
            }
            val removeAction = simpleAction("移除", "移除选中变量", HttpIcons.remove) {
                val selected = table.selectedRows
                if (selected.isEmpty()) {
                    if (model.rowCount > 0) {
                        model.removeRow(model.rowCount - 1)
                    }
                } else {
                    selected.sortedDescending().forEach { model.removeRow(it) }
                }
            }
            val clearAction = simpleAction("清空", "清空当前页签变量", HttpIcons.clear) {
                model.setRowCount(0)
            }
            val toolbar = buildActionToolbar(
                "HttpScriptEnvToolbar",
                listOf(addAction, removeAction, clearAction),
                table
            )
            return JPanel(BorderLayout(0, 6)).apply {
                add(toolbar.component, BorderLayout.NORTH)
                add(JBScrollPane(table), BorderLayout.CENTER)
            }
        }

        private fun loadModel(model: DefaultTableModel, values: Map<String, String>) {
            model.setRowCount(0)
            values.entries.sortedBy { it.key }.forEach { (key, value) ->
                model.addRow(arrayOf(key, value))
            }
        }

        private fun readModel(model: DefaultTableModel): Map<String, String> {
            val result = linkedMapOf<String, String>()
            for (row in 0 until model.rowCount) {
                val key = (model.getValueAt(row, 0) as? String)?.trim().orEmpty()
                val value = (model.getValueAt(row, 1) as? String)?.trim().orEmpty()
                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key] = value
                }
            }
            return result
        }
    }

    private inner class ScopedScriptManageDialog(
        project: Project,
        private val phase: HttpScriptPhase,
        private val scope: HttpScriptScope,
        private val moduleName: String = ""
    ) : DialogWrapper(project) {
        private val scripts = mutableListOf<HttpScopedScriptEntry>()
        private val model = object : DefaultTableModel(arrayOf("启用", "名称"), 0) {
            override fun getColumnClass(columnIndex: Int): Class<*> {
                return if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
            }

            override fun isCellEditable(row: Int, column: Int): Boolean {
                return true
            }
        }
        private val table = JBTable(model)
        private val editor = MultiLanguageTextField(scriptFileType, this@HttpClientPanel.project)
        private var selectedRow = -1
        private var syncing = false

        init {
            title = buildDialogTitle()
            scripts.addAll(loadScripts())
            init()
            reloadModel()
            bindListeners()
            if (scripts.isNotEmpty()) {
                table.selectionModel.setSelectionInterval(0, 0)
            }
        }

        override fun createCenterPanel(): JComponent {
            table.rowHeight = JBUI.scale(24)
            table.setShowGrid(false)
            table.emptyText.text = "暂无脚本"
            table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            table.columnModel.getColumn(0).apply {
                minWidth = JBUI.scale(56)
                maxWidth = JBUI.scale(56)
                preferredWidth = JBUI.scale(56)
            }
            val addAction = simpleAction("新增", "新增脚本", HttpIcons.add) {
                commitCurrentEditor()
                scripts.add(
                    HttpScopedScriptEntry(
                        name = "脚本${scripts.size + 1}",
                        enabled = true,
                        content = ""
                    )
                )
                reloadModel()
                val row = scripts.lastIndex
                if (row >= 0) {
                    table.selectionModel.setSelectionInterval(row, row)
                }
            }
            val removeAction = simpleAction("删除", "删除选中脚本", HttpIcons.remove) {
                val selected = table.selectedRow
                if (selected !in scripts.indices) {
                    return@simpleAction
                }
                commitCurrentEditor()
                scripts.removeAt(selected)
                reloadModel()
                if (scripts.isEmpty()) {
                    selectedRow = -1
                    editor.text = ""
                } else {
                    val next = minOf(selected, scripts.lastIndex)
                    table.selectionModel.setSelectionInterval(next, next)
                }
            }
            val upAction = object : AnAction("上移", "上移脚本顺序", AllIcons.Actions.MoveUp) {
                override fun actionPerformed(e: AnActionEvent) {
                    val row = table.selectedRow
                    if (row !in scripts.indices || row == 0) {
                        return
                    }
                    commitCurrentEditor()
                    Collections.swap(scripts, row, row - 1)
                    reloadModel()
                    table.selectionModel.setSelectionInterval(row - 1, row - 1)
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }
            }
            val downAction = object : AnAction("下移", "下移脚本顺序", AllIcons.Actions.MoveDown) {
                override fun actionPerformed(e: AnActionEvent) {
                    val row = table.selectedRow
                    if (row !in scripts.indices || row >= scripts.lastIndex) {
                        return
                    }
                    commitCurrentEditor()
                    Collections.swap(scripts, row, row + 1)
                    reloadModel()
                    table.selectionModel.setSelectionInterval(row + 1, row + 1)
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }
            }
            val toolbar = buildActionToolbar(
                "HttpScopedScriptDialogToolbar.$scope.$phase",
                listOf(addAction, removeAction, upAction, downAction),
                table
            )
            val split = JBSplitter(false, 0.34f)
            split.firstComponent = JPanel(BorderLayout(0, 6)).apply {
                add(toolbar.component, BorderLayout.NORTH)
                add(JBScrollPane(table), BorderLayout.CENTER)
            }
            split.secondComponent = editor
            split.dividerWidth = JBUI.scale(6)
            split.proportion = 0.34f
            return JPanel(BorderLayout(0, 8)).apply {
                preferredSize = JBUI.size(920, 560)
                add(JBLabel(buildDialogHint()), BorderLayout.NORTH)
                add(split, BorderLayout.CENTER)
            }
        }

        override fun doOKAction() {
            commitCurrentEditor()
            stopTableEditing()
            saveScripts(scripts)
            super.doOKAction()
        }

        private fun buildDialogTitle(): String {
            val phaseText = if (phase == HttpScriptPhase.PRE) "前置" else "后置"
            return when (scope) {
                HttpScriptScope.GLOBAL -> "全局${phaseText}脚本"
                HttpScriptScope.PROJECT -> "项目${phaseText}脚本"
                HttpScriptScope.MODULE -> "模块${phaseText}脚本 - $moduleName"
                HttpScriptScope.INTERFACE -> "接口${phaseText}脚本"
            }
        }

        private fun buildDialogHint(): String {
            val executionOrder = "执行顺序：全局 -> 项目 -> 模块 -> 接口"
            return when (scope) {
                HttpScriptScope.GLOBAL -> "$executionOrder；当前正在编辑全局脚本。"
                HttpScriptScope.PROJECT -> "$executionOrder；当前正在编辑项目脚本。"
                HttpScriptScope.MODULE -> "$executionOrder；当前模块：$moduleName。"
                HttpScriptScope.INTERFACE -> "$executionOrder；接口脚本固定为单个，不在此面板编辑。"
            }
        }

        private fun bindListeners() {
            table.selectionModel.addListSelectionListener {
                if (it.valueIsAdjusting || syncing) {
                    return@addListSelectionListener
                }
                val row = table.selectedRow
                if (row == selectedRow) {
                    return@addListSelectionListener
                }
                commitCurrentEditor()
                selectedRow = row
                editor.text = scripts.getOrNull(row)?.content.orEmpty()
            }
            model.addTableModelListener { event ->
                if (syncing) {
                    return@addTableModelListener
                }
                val first = event.firstRow
                val last = event.lastRow
                if (first < 0 || last < 0) {
                    return@addTableModelListener
                }
                for (row in first..last) {
                    syncRowToScript(row)
                }
            }
        }

        private fun syncRowToScript(row: Int) {
            val script = scripts.getOrNull(row) ?: return
            script.enabled = (model.getValueAt(row, 0) as? Boolean) ?: true
            script.name = (model.getValueAt(row, 1) as? String)?.trim().orEmpty()
        }

        private fun stopTableEditing() {
            if (table.isEditing) {
                table.cellEditor?.stopCellEditing()
            }
        }

        private fun commitCurrentEditor() {
            val row = selectedRow
            if (row !in scripts.indices) {
                return
            }
            scripts[row].content = editor.text
            syncRowToScript(row)
        }

        private fun reloadModel() {
            stopTableEditing()
            syncing = true
            try {
                model.setRowCount(0)
                scripts.forEach { script ->
                    model.addRow(arrayOf(script.enabled, script.name))
                }
            } finally {
                syncing = false
            }
        }

        private fun loadScripts(): MutableList<HttpScopedScriptEntry> {
            return when (scope) {
                HttpScriptScope.GLOBAL -> HttpScopedScriptStore.loadGlobal(phase)
                HttpScriptScope.PROJECT -> HttpScopedScriptStore.loadProject(this@HttpClientPanel.project, phase)
                HttpScriptScope.MODULE -> HttpScopedScriptStore.loadModule(this@HttpClientPanel.project, moduleName, phase)
                HttpScriptScope.INTERFACE -> mutableListOf()
            }
        }

        private fun saveScripts(values: List<HttpScopedScriptEntry>) {
            when (scope) {
                HttpScriptScope.GLOBAL -> HttpScopedScriptStore.saveGlobal(phase, values)
                HttpScriptScope.PROJECT -> HttpScopedScriptStore.saveProject(this@HttpClientPanel.project, phase, values)
                HttpScriptScope.MODULE -> HttpScopedScriptStore.saveModule(this@HttpClientPanel.project, moduleName, phase, values)
                HttpScriptScope.INTERFACE -> Unit
            }
        }
    }

    private inner class ScriptHelpDialog(
        project: Project,
        private val phase: HttpScriptPhase
    ) : DialogWrapper(project) {
        init {
            title = if (phase == HttpScriptPhase.PRE) "前置脚本说明" else "后置脚本说明"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val apiField = createViewerField()
            apiField.text = SCRIPT_API_DOC
            apiField.setCaretPosition(0)

            val phaseField = createViewerField()
            phaseField.text = if (phase == HttpScriptPhase.PRE) PRE_SCRIPT_DOC else POST_SCRIPT_DOC
            phaseField.setCaretPosition(0)

            val sampleField = createViewerField()
            sampleField.text = if (phase == HttpScriptPhase.PRE) PRE_SCRIPT_TEMPLATE else POST_SCRIPT_TEMPLATE
            sampleField.setCaretPosition(0)

            val tabs = JBTabbedPane()
            tabs.addTab("通用 API", apiField)
            tabs.addTab("当前阶段", phaseField)
            tabs.addTab("示例", sampleField)

            return JPanel(BorderLayout()).apply {
                preferredSize = JBUI.size(760, 560)
                add(tabs, BorderLayout.CENTER)
            }
        }
    }

    private inner class ScriptSnippetDialog(
        project: Project,
        options: Array<String>
    ) : DialogWrapper(project) {
        private val list = JBList(*options)
        var selectedIndex: Int = -1
            private set

        init {
            title = "脚本片段"
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION
            if (options.isNotEmpty()) {
                list.selectedIndex = 0
            }
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && list.selectedIndex >= 0) {
                        doOKAction()
                    }
                }
            })
            init()
        }

        override fun createCenterPanel(): JComponent {
            return JPanel(BorderLayout(0, 6)).apply {
                preferredSize = JBUI.size(420, 260)
                add(JBLabel("选择要插入的片段"), BorderLayout.NORTH)
                add(JBScrollPane(list), BorderLayout.CENTER)
            }
        }

        override fun doOKAction() {
            selectedIndex = list.selectedIndex
            super.doOKAction()
        }
    }

    private inner class DocFieldEditDialog(
        project: Project,
        initialKey: String,
        initialValue: String,
        initialDescription: String
    ) : DialogWrapper(project) {
        private val keyField = JBTextField(initialKey)
        private val valueField = JBTextField(initialValue)
        private val descriptionField = JBTextField(initialDescription)

        val fieldPath: String
            get() = keyField.text
        val fieldValue: String
            get() = valueField.text
        val fieldDescription: String
            get() = descriptionField.text

        init {
            title = "编辑字段"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(GridBagLayout())
            val c = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 0.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(4)
            }
            panel.add(JBLabel("字段路径"), c)
            c.gridx = 1
            c.weightx = 1.0
            panel.add(keyField, c)

            c.gridx = 0
            c.gridy++
            c.weightx = 0.0
            panel.add(JBLabel("示例值"), c)
            c.gridx = 1
            c.weightx = 1.0
            panel.add(valueField, c)

            c.gridx = 0
            c.gridy++
            c.weightx = 0.0
            panel.add(JBLabel("描述"), c)
            c.gridx = 1
            c.weightx = 1.0
            panel.add(descriptionField, c)

            return panel
        }

        override fun doValidate(): ValidationInfo? {
            if (keyField.text.trim().isBlank()) {
                return ValidationInfo("字段路径不能为空", keyField)
            }
            return null
        }
    }

    private inner class ImportApiDialog(
        project: Project
    ) : DialogWrapper(project) {
        private val sourceBox = JComboBox(IMPORT_SOURCE_OPTIONS)
        private val formLayout = CardLayout()
        private val formPanel = JPanel(formLayout)
        private val filePathField = JBTextField()
        private val urlField = JBTextField("http://localhost:8080/v3/api-docs")
        private val jsonEditor = MultiLanguageTextField(JsonFileType.INSTANCE, project)

        val sourceType: HttpApiSpecImportService.SourceType
            get() = when (sourceBox.selectedIndex) {
                1 -> HttpApiSpecImportService.SourceType.URL
                2 -> HttpApiSpecImportService.SourceType.JSON
                else -> HttpApiSpecImportService.SourceType.FILE
            }

        val filePath: String
            get() = filePathField.text.trim()

        val url: String
            get() = urlField.text.trim()

        val json: String
            get() = jsonEditor.text.trim()

        init {
            title = "导入 OpenAPI/Swagger"
            sourceBox.addActionListener { switchForm() }
            init()
            switchForm()
        }

        override fun createCenterPanel(): JComponent {
            val filePanel = JPanel(BorderLayout(0, 6))
            val fileRow = JPanel(BorderLayout(6, 0))
            val chooseButton = JButton("选择文件")
            chooseButton.addActionListener { chooseImportFile() }
            fileRow.add(filePathField, BorderLayout.CENTER)
            fileRow.add(chooseButton, BorderLayout.EAST)
            filePanel.add(fileRow, BorderLayout.NORTH)
            filePanel.add(JBLabel("请选择本地 OpenAPI/Swagger JSON 文件。"), BorderLayout.CENTER)

            val urlPanel = JPanel(BorderLayout(0, 6))
            urlPanel.add(JBLabel("可使用设置中的代理配置发起远程导入。"), BorderLayout.NORTH)
            urlPanel.add(urlField, BorderLayout.NORTH)

            val jsonPanel = JPanel(BorderLayout(0, 6))
            jsonPanel.add(JBLabel("请粘贴 OpenAPI/Swagger JSON 文档"), BorderLayout.NORTH)
            jsonPanel.add(jsonEditor, BorderLayout.CENTER)

            formPanel.add(filePanel, HttpApiSpecImportService.SourceType.FILE.name)
            formPanel.add(urlPanel, HttpApiSpecImportService.SourceType.URL.name)
            formPanel.add(jsonPanel, HttpApiSpecImportService.SourceType.JSON.name)

            val panel = JPanel(BorderLayout(0, 8))
            panel.preferredSize = JBUI.size(760, 360)
            panel.add(JBLabel("导入方式"), BorderLayout.NORTH)
            val top = JPanel(BorderLayout(0, 6))
            top.add(sourceBox, BorderLayout.NORTH)
            top.add(formPanel, BorderLayout.CENTER)
            panel.add(top, BorderLayout.CENTER)
            return panel
        }

        override fun doValidate(): ValidationInfo? {
            return when (sourceType) {
                HttpApiSpecImportService.SourceType.FILE -> {
                    if (filePath.isBlank()) {
                        ValidationInfo("请选择 JSON 文件", filePathField)
                    } else {
                        val path = runCatching { Paths.get(filePath) }.getOrNull()
                        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
                            ValidationInfo("文件不存在或不可读", filePathField)
                        } else {
                            null
                        }
                    }
                }
                HttpApiSpecImportService.SourceType.URL -> {
                    if (url.isBlank()) {
                        ValidationInfo("请输入 URL", urlField)
                    } else if (runCatching { URI(url) }.isFailure) {
                        ValidationInfo("URL 格式不正确", urlField)
                    } else {
                        null
                    }
                }
                HttpApiSpecImportService.SourceType.JSON -> {
                    if (json.isBlank()) {
                        ValidationInfo("JSON 不能为空", jsonEditor)
                    } else {
                        null
                    }
                }
            }
        }

        private fun switchForm() {
            formLayout.show(formPanel, sourceType.name)
        }

        private fun chooseImportFile() {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            descriptor.title = "选择 OpenAPI/Swagger JSON 文件"
            FileChooser.chooseFile(descriptor, project, null) { file ->
                filePathField.text = file.path
            }
        }
    }

    private inner class ExportApiDialog(
        project: Project,
        requestCount: Int
    ) : DialogWrapper(project) {
        private val formatBox = JComboBox(EXPORT_FORMAT_OPTIONS)
        private val titleField = JBTextField("HTTP API")
        private val versionField = JBTextField("1.0.0")
        private val serverField = JBTextField("")
        private val countLabel = JBLabel("将导出 $requestCount 个接口")

        val format: String
            get() = (formatBox.selectedItem as? ExportFormatOption)?.format ?: "openapi"

        val titleValue: String
            get() = titleField.text.trim().ifBlank { "HTTP API" }

        val versionValue: String
            get() = versionField.text.trim().ifBlank { "1.0.0" }

        val serverUrlValue: String
            get() = serverField.text.trim()

        init {
            title = "导出接口文档"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(GridBagLayout())
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                gridx = 0
                gridy = 0
                insets = JBUI.insets(4)
            }
            panel.preferredSize = JBUI.size(420, 0)

            panel.add(countLabel, c)
            c.gridy++
            panel.add(JBLabel("导出格式"), c)
            c.gridy++
            panel.add(formatBox, c)

            c.gridy++
            panel.add(JBLabel("文档标题"), c)
            c.gridy++
            panel.add(titleField, c)

            c.gridy++
            panel.add(JBLabel("版本"), c)
            c.gridy++
            panel.add(versionField, c)

            c.gridy++
            panel.add(JBLabel("服务地址(可选)"), c)
            c.gridy++
            panel.add(serverField, c)

            return panel
        }

        override fun doValidate(): ValidationInfo? {
            if (titleField.text.trim().isBlank()) {
                return ValidationInfo("文档标题不能为空", titleField)
            }
            if (versionField.text.trim().isBlank()) {
                return ValidationInfo("版本不能为空", versionField)
            }
            return null
        }
    }

    private data class ScriptSnippet(
        val title: String,
        val content: String
    )

    private data class ResolvedScopedScript(
        val scope: HttpScriptScope,
        val name: String,
        val label: String,
        val content: String
    )

    private data class BodyTypeOption(val label: String, val type: HttpBodyType) {
        override fun toString(): String {
            return label
        }
    }

    private data class ExportFormatOption(val label: String, val format: String) {
        override fun toString(): String {
            return label
        }
    }

    private data class TemplatePreviewContext(
        val requestVars: Map<String, String>,
        val pathVars: Map<String, String>,
        val projectEnv: Map<String, String>,
        val globalEnv: Map<String, String>,
        val resolveOrder: HttpVariableTemplateSettings.ResolveOrder
    )

    private data class TemplateTokenResult(
        val expression: String,
        val value: String?,
        val source: String?,
        val reason: String?,
        val displayText: String = "{{${expression}}}"
    )

    private data class TemplateInspectionResult(
        val tokens: List<TemplateTokenResult>
    ) {
        fun hasTemplate(): Boolean {
            return tokens.isNotEmpty()
        }

        fun hasUnresolved(): Boolean {
            return tokens.any { it.value == null }
        }
    }

    private data class DocFieldPathSegment(
        val name: String,
        val isArray: Boolean,
        val pathToken: String
    )

    private data class DocFieldTreeItem(
        val name: String,
        val fullPath: String,
        val isArray: Boolean,
        var example: String = "",
        var description: String = ""
    )

    companion object {
        private val TEMPLATE_EXPRESSION_REGEX = Regex("\\{\\{\\s*([^{}]+?)\\s*\\}\\}")
        private val URL_PATH_PLACEHOLDER_REGEX = Regex("(?<!\\{)\\{\\s*([^{}]+?)\\s*\\}(?!\\})")
        private val TEMPLATE_MATCHED_COLOR = JBColor(Color(0x1B5E20), Color(0x81C784))
        private val TEMPLATE_UNMATCHED_COLOR = JBColor(Color(0xB71C1C), Color(0xEF9A9A))
        private val URL_MATCHED_BACKGROUND = JBColor(Color(0xE8F5E9), Color(0x1F2F23))
        private val URL_UNMATCHED_BACKGROUND = JBColor(Color(0xFFEBEE), Color(0x3A2222))
        private val VARIABLE_TEMPLATE_HELP_HTML = """
            <html>
            <b>1. 语法</b><br/>
            - 使用 <code>{{name}}</code> 或 <code>{{namespace.key}}</code>。<br/>
            - 仅替换 <code>{{...}}</code>，其他文本保持不变。<br/><br/>
            <b>2. 变量来源</b><br/>
            - 接口变量：请求页签「变量」中的键值，命名空间 <code>{{api.token}}</code> / <code>{{request.token}}</code> / <code>{{vars.token}}</code>。<br/>
            - 路径变量：请求页签「路径变量」中的键值，命名空间 <code>{{path.id}}</code>。<br/>
            - 项目环境：请求区域右上角「环境变量」按钮中的「项目环境」，命名空间 <code>{{project.token}}</code>。<br/>
            - 全局环境：请求区域右上角「环境变量」按钮中的「全局环境」，命名空间 <code>{{global.token}}</code>。<br/>
            - env 命名空间：<code>{{env.token}}</code>，读取顺序固定为「项目环境 -> 全局环境」。<br/><br/>
            <b>3. 无命名空间变量</b><br/>
            - <code>{{token}}</code> 使用固定优先级：接口变量 -> 项目环境 -> 全局环境。<br/><br/>
            <b>4. 未解析处理</b><br/>
            - 保留原文：保持 <code>{{token}}</code> 不变继续发送。<br/>
            <b>5. 颜色与悬浮提示</b><br/>
            - 绿色：全部变量匹配成功。<br/>
            - 红色：存在未匹配变量。<br/>
            - 鼠标悬浮可查看每个变量的解析值、来源或未匹配原因。<br/>
            </html>
        """.trimIndent()
        private val HTTP_METHODS = arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        private val BODY_TYPES = arrayOf(
            BodyTypeOption("无", HttpBodyType.NONE),
            BodyTypeOption("JSON", HttpBodyType.JSON),
            BodyTypeOption("x-www-form-urlencoded", HttpBodyType.FORM_URLENCODED),
            BodyTypeOption("form-data", HttpBodyType.FORM_DATA)
        )
        private val PROXY_TYPES = arrayOf("HTTP", "SOCKS")
        private val IMPORT_SOURCE_OPTIONS = arrayOf("文件导入", "网络地址导入", "粘贴 JSON 导入")
        private val EXPORT_FORMAT_OPTIONS = arrayOf(
            ExportFormatOption("OpenAPI 3.0 (JSON)", "openapi"),
            ExportFormatOption("Swagger 2.0 (JSON)", "swagger"),
            ExportFormatOption("HTML 文档", "html"),
            ExportFormatOption("PDF 文档", "pdf")
        )
        private val PRE_SCRIPT_SNIPPETS = listOf(
            ScriptSnippet("读取环境变量", "var token = env.get(\"token\");\nif (token) {\n  request.headers.Authorization = \"Bearer \" + token;\n}"),
            ScriptSnippet("设置临时变量 vars", "vars.traceId = \"trace-\" + Date.now();\nrequest.headers[\"X-Trace-Id\"] = vars.traceId;"),
            ScriptSnippet("调用项目依赖 AES", "var AES = jvm.type(\"com.company.common.crypto.AES\");\nvar encryptor = AES.ECB.buildEncrypt(env.get(\"key\"));\nvar result = encryptor.getBase64(env.get(\"plain\"));\nif (result && result.data) {\n  vars.cipher = result.data;\n} else {\n  vars.cipher = String(result);\n}"),
            ScriptSnippet("切换 bodyMode 到 JSON", "request.bodyMode = \"JSON\";\nrequest.jsonBody = JSON.stringify({ id: 1, name: \"demo\" });"),
            ScriptSnippet("切换 bodyMode 到 x-www-form-urlencoded", "request.bodyMode = \"FORM_URLENCODED\";\nrequest.urlEncoded = { grant_type: \"client_credentials\", scope: \"read\" };"),
            ScriptSnippet("切换 bodyMode 到 form-data", "request.bodyMode = \"FORM_DATA\";\nrequest.formData = [\n  { key: \"name\", value: \"demo\", type: \"TEXT\" },\n  { key: \"file\", value: \"/tmp/a.txt\", type: \"FILE\" }\n];"),
            ScriptSnippet("设置 Cookie", "request.cookies.session = \"abc123\";\nrequest.cookies.locale = \"zh-CN\";"),
            ScriptSnippet("持久化到项目环境变量", "store.setProject(\"token\", \"xxx\");\nlog(\"token 已写入项目环境变量\");")
        )
        private val POST_SCRIPT_SNIPPETS = listOf(
            ScriptSnippet("读取响应并保存 vars", "if (response.status === 200 && response.body) {\n  var data = JSON.parse(response.body);\n  vars.userId = data.id;\n}"),
            ScriptSnippet("写回响应头/响应体", "response.headers[\"X-Handled-By\"] = \"jtools-script\";\nresponse.body = response.body || \"\";"),
            ScriptSnippet("保存 token 到项目环境变量", "if (response.body) {\n  var data = JSON.parse(response.body);\n  if (data.token) {\n    store.setProject(\"token\", data.token);\n  }\n}"),
            ScriptSnippet("处理 Set-Cookie", "response.cookies.push({\n  name: \"session\",\n  value: \"new-value\",\n  domain: \"\",\n  path: \"/\",\n  expiresAt: 0,\n  secure: false,\n  httpOnly: true,\n  remove: false\n});"),
            ScriptSnippet("删除 Cookie", "response.cookies.push({\n  name: \"session\",\n  value: \"\",\n  domain: \"\",\n  path: \"/\",\n  expiresAt: 0,\n  secure: false,\n  httpOnly: false,\n  remove: true\n});")
        )
        private val SCRIPT_API_DOC = """
            【执行约定】
            - 前置脚本：发送 HTTP 请求之前执行
            - 后置脚本：拿到响应之后执行
            - 多作用域执行顺序：全局 -> 项目 -> 模块 -> 接口
            - 仅启用且脚本内容非空时才会执行
            - 返回值：脚本 return 值会被忽略，不需要 return
            - 生效方式：直接修改 request / response / vars 对象
            - 超时：脚本执行超时会报错并写入状态信息

            【可用对象】
            1) request (前置可用，后置只读参考)
               method: String
               url: String
               timeoutSeconds: Number
               pathParams: Map<String, String>
               params: Map<String, String>
               headers: Map<String, String>
               cookies: Map<String, String>
               bodyMode: String，可选 NONE / JSON / FORM_URLENCODED / FORM_DATA
               jsonBody: String | null
               urlEncoded: Map<String, String>
               formData: Array<{key,value,type}>，type 可选 TEXT / FILE

            2) response (仅后置可用)
               status: Number
               statusText: String
               headers: Map<String, String>
               body: String | null
               bodyBase64: String | null
               cookies: Array<{name,value,domain,path,expiresAt,secure,httpOnly,remove}>

            3) env (只读环境变量)
               env.get(key): String | null
               env.getProject(key): String | null
               env.getGlobal(key): String | null
               env.all(): Map<String, String>
               读取优先级：project > global
               设置入口：请求区域右上角“环境变量”按钮

            4) vars (临时变量)
               - 类型：Map
               - 生命周期：仅本次请求
               - 用途：前置脚本和后置脚本之间传值

            5) store (持久化变量写入)
               store.get(key): String | null
               store.getProject(key): String | null
               store.getGlobal(key): String | null
               store.setProject(key, value): void
               store.setGlobal(key, value): void
               store.removeProject(key): void
               store.removeGlobal(key): void

            6) 日志函数
               log(message): void
               log.info(message): void
               log.debug(message): void
               log.warn(message): void
               log.error(message): void
               debug(message): void
               warn(message): void
               error(message): void

            7) jvm (调用项目依赖类)
               jvm.type("com.xxx.ClassName"): 可直接调用静态方法的类对象
               jvm.available("com.xxx.ClassName"): boolean
               jvm.classpath(): Array<String>
               示例：
               var AES = jvm.type("com.company.common.crypto.AES");
               var encryptor = AES.ECB.buildEncrypt(env.get("key"));
               var result = encryptor.getBase64();
               vars.cipher = result && result.data ? result.data : String(result);

            8) endpoint (代码来源元数据，只读)
               source: String，接口来源（METHOD / ROUTER）
               methodAnnotations: Map<String, Map<String, String>>
                 - key: 注解全限定名
                 - value: 注解属性名 -> 属性值
               parameters: Map<String, {type: String, annotations: Map<String, Map<String, String>>}>
                 - key: 参数名
                 - value.type: 参数类型全限定名/规范名
                 - value.annotations: 参数注解（结构同 methodAnnotations）
               methodBody: String | null，方法体源码文本
               methodDescriptor: Map | null
                 - name: 方法名
                 - declaringClass: 声明类全限定名
                 - returnType: 返回值类型
                 - parameterTypes: 参数类型列表
                 - throwsTypes: throws 类型列表
                 - modifiers: 修饰符列表
               classDescriptor: Map | null
                 - name / qualifiedName / superClass
                 - interfaces: 接口全限定名列表
                 - modifiers: 修饰符列表
                 - annotations: 类注解（结构同 methodAnnotations）

               默认值说明：
               - 若请求不是“从代码跳转/添加”生成，methodAnnotations/parameters 为 {}，methodBody/methodDescriptor/classDescriptor 为 null。
               - 若能解析到方法但缺少某部分信息，则对应字段返回空 Map 或 null，不抛错。
        """.trimIndent()
        private val PRE_SCRIPT_DOC = """
            【前置脚本入参】
            - request: 可读可写
            - endpoint: 只读（接口源码元数据，见通用 API）
            - env: 只读
            - vars: 可读可写
            - store: 可读可写
            - jvm: 可读（用于加载项目 classpath 类）
            - log/debug/warn/error

            【前置脚本返回值】
            - 无需返回，return 会被忽略
            - 通过修改 request 对象生效

            【前置脚本建议】
            - URL、Header、Query、Cookie、Body 在这里统一处理
            - bodyMode 和 body 数据结构要匹配
            - 需要项目依赖能力时，用 jvm.type("全限定类名")
        """.trimIndent()
        private val POST_SCRIPT_DOC = """
            【后置脚本入参】
            - request: 只读参考
            - response: 可读可写
            - endpoint: 只读（接口源码元数据，见通用 API）
            - env: 只读
            - vars: 可读可写
            - store: 可读可写
            - jvm: 可读（用于加载项目 classpath 类）
            - log/debug/warn/error

            【后置脚本返回值】
            - 无需返回，return 会被忽略
            - 通过修改 response 对象和 response.cookies 生效

            【后置脚本建议】
            - 解析响应体后保存 token 到 store
            - 按业务规则重写响应头/响应体
            - 用 vars 承接前置脚本生成的上下文
        """.trimIndent()
        private val PRE_SCRIPT_TEMPLATE = """
            // 前置脚本示例：发送前动态改写请求
            var token = env.get("token");
            if (token) {
              request.headers.Authorization = "Bearer " + token;
            }

            vars.traceId = "trace-" + Date.now();
            request.headers["X-Trace-Id"] = vars.traceId;

            // 代码来源接口可读取 endpoint 元数据（非代码来源时为默认空值）
            if (endpoint.methodDescriptor) {
              log.info("method=" + endpoint.methodDescriptor.name);
            }

            // 根据 bodyMode 设置请求体
            request.bodyMode = "JSON";
            request.jsonBody = JSON.stringify({
              name: "demo",
              timestamp: Date.now()
            });

            log("pre done, url=" + request.url);
        """.trimIndent()
        private val POST_SCRIPT_TEMPLATE = """
            // 后置脚本示例：处理响应并落库变量
            log("post status=" + response.status);

            if (response.status === 200 && response.body) {
              var data = JSON.parse(response.body);
              if (data.token) {
                store.setProject("token", data.token);
              }
            }

            // 也可以改写响应内容（用于视图展示）
            response.headers["X-Handled-By"] = "script";
        """.trimIndent()
        private const val MAX_TIMEOUT_SECONDS = 120
        private const val MAX_BODY_BYTES = 1_000_000
        private const val MIN_PREVIEW_CHARS = 1000
        private const val MAX_PREVIEW_CHARS = 2_000_000
        private const val SCRIPT_TIMEOUT_MS = 1_000L
        private const val RESPONSE_TAB_RAW_INDEX = 0
        private const val RESPONSE_TAB_RENDER_INDEX = 1
        private const val RESPONSE_TAB_HEADERS_INDEX = 2
        private const val RESPONSE_TAB_REQUEST_HEADERS_INDEX = 3
        private const val RESPONSE_TAB_REQUEST_INFO_INDEX = 4
        private const val HISTORY_LABEL_LIMIT = 80
        private const val MAX_HISTORY = 200
        private val PATH_VARIABLE_REGEX = "\\{([^}]+)}".toRegex()
        private val RESTRICTED_HEADERS = setOf(
            "connection",
            "content-length",
            "expect",
            "host",
            "upgrade"
        )
    }
}
