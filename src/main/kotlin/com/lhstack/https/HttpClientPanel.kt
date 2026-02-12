package com.lhstack.https

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.json.JsonFileType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
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
import java.io.InputStream
import java.net.HttpCookie
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

    private val methodBox = JComboBox(HTTP_METHODS)
    private val urlField = JBTextField()
    private val timeoutField = JBTextField(uiSettings.defaultTimeoutSeconds.toString())
    private val sendButton = JButton("发送")
    private val cancelButton = JButton("取消")
    private val copyCurlButton = JButton("复制 cURL")
    private val saveApiButton = JButton("保存接口")
    private val historyButton = JButton("历史请求")
    private val requestHistoryButton = JButton("历史")

    private val pathParamsModel = DefaultTableModel(arrayOf("键", "值"), 0)
    private val paramsModel = DefaultTableModel(arrayOf("键", "值"), 0)
    private val headersModel = DefaultTableModel(arrayOf("键", "值"), 0)
    private val urlEncodedModel = DefaultTableModel(arrayOf("键", "值"), 0)
    private val formDataModel = DefaultTableModel(arrayOf("键", "值", "类型"), 0)
    private val cookiesModel = DefaultTableModel(arrayOf("名称", "值", "域", "路径", "过期时间", "安全", "HttpOnly"), 0)
    private val cookieEntries = mutableListOf<HttpCookieEntry>()
    private lateinit var cookiesTable: JBTable
    private lateinit var formDataTable: JBTable
    private val bodyTypeBox = JComboBox(BODY_TYPES)
    private val bodyCardLayout = CardLayout()
    private val bodyCardPanel = JPanel(bodyCardLayout)
    private val bodyArea = MultiLanguageTextField(JsonFileType.INSTANCE, project)

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
    private val responseDownloadButton = JButton("保存文件")
    private lateinit var responseTabs: JBTabbedPane

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

    init {
        border = JBUI.Borders.empty(8)
        buildHistoryPanel()
        loadApiData()
        loadCallTabs()
        loadCookies()
    }

    fun disposePanel() {
        Disposer.dispose(disposable)
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
            paramsModel.setRowCount(0)
            headersModel.setRowCount(0)
            urlEncodedModel.setRowCount(0)
            formDataModel.setRowCount(0)
            cookiesModel.setRowCount(0)
            cookieEntries.clear()
            bodyArea.text = ""
            responseRawArea.text = ""
            responseRenderArea.text = ""
            responseRenderJsonArea.text = ""
            responseRenderXmlArea.text = ""
            responseRenderHtml.setHtml("")
            responseRenderImage.icon = null
            responseRenderInfo.text = ""
            responseHeadersArea.text = ""
            responseRequestHeadersArea.text = ""
            responseDownloadButton.isEnabled = false
        } finally {
            isLoading = false
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
        applySettings(updated)
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
        apiTree.selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
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

        val newGroupButton = JButton("新建分组")
        newGroupButton.addActionListener { createGroup(null) }
        historyButton.addActionListener { showHistoryDialog() }

        val apiHeader = JPanel(BorderLayout(6, 0))
        apiHeader.add(JBLabel("接口列表"), BorderLayout.WEST)
        val apiActions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        apiActions.add(historyButton)
        apiActions.add(newGroupButton)
        apiHeader.add(apiActions, BorderLayout.EAST)

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
        val newTabButton = JButton("新建")
        newTabButton.isFocusable = false
        newTabButton.addActionListener { createNewTab() }

        val tabBar = JPanel(BorderLayout(6, 0))
        tabBar.add(callTabsPane.component, BorderLayout.CENTER)
        tabBar.add(newTabButton, BorderLayout.EAST)

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
        actionPanel.add(saveApiButton)
        actionPanel.add(requestHistoryButton)
        actionPanel.add(sendButton)
        actionPanel.add(cancelButton)
        actionPanel.add(copyCurlButton)
        val actionRow = JPanel(BorderLayout())
        actionRow.add(actionPanel, BorderLayout.EAST)
        requestLine.add(urlRow, BorderLayout.NORTH)
        requestLine.add(actionRow, BorderLayout.SOUTH)

        sendButton.addActionListener { sendCurrentRequest() }
        cancelButton.addActionListener { cancelCurrentRequest() }
        copyCurlButton.addActionListener { copyCurl() }
        saveApiButton.addActionListener { saveCurrentRequest() }
        requestHistoryButton.addActionListener { showCurrentRequestHistory() }
        cancelButton.isEnabled = false

        val pathParamsPanel = createKeyValuePanel(pathParamsModel, "路径变量")
        val paramsPanel = createKeyValuePanel(paramsModel, "查询参数")
        val headersPanel = createKeyValuePanel(headersModel, "请求头")
        val bodyPanel = buildBodyPanel()
        val cookiesPanel = createCookiePanel()

        val requestTabs = JBTabbedPane()
        requestTabs.addTab("路径变量", pathParamsPanel)
        requestTabs.addTab("参数", paramsPanel)
        requestTabs.addTab("请求头", headersPanel)
        requestTabs.addTab("请求体", bodyPanel)
        requestTabs.addTab("Cookie", cookiesPanel)

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
        binaryPanel.add(responseDownloadButton, BorderLayout.WEST)
        responseRenderPanel.add(binaryPanel, "binary")

        responseDownloadButton.addActionListener { saveResponseToFile() }

        responseTabs = JBTabbedPane()
        responseTabs.addTab("原始", responseRawArea)
        responseTabs.addTab("渲染", responseRenderPanel)
        responseTabs.addTab("响应头", responseHeadersArea)
        responseTabs.addTab("请求头", responseRequestHeadersArea)
        responseTabs.addTab("请求信息", responseRequestSummaryArea)
        responseTabs.addChangeListener {
            renderTabIfNeeded(responseTabs.selectedIndex)
        }

        val responsePanel = JPanel(BorderLayout(0, 6))
        responsePanel.add(responseSummary, BorderLayout.NORTH)
        responsePanel.add(responseTabs, BorderLayout.CENTER)

        val requestResponseSplit = JBSplitter(true, 0.55f)
        requestResponseSplit.firstComponent = requestTabs
        requestResponseSplit.secondComponent = responsePanel
        requestResponseSplit.border = JBUI.Borders.empty()
        requestResponseSplit.setDividerWidth(JBUI.scale(6))
        requestResponseSplit.setShowDividerControls(true)
        requestResponseSplit.setShowDividerIcon(true)
        requestResponseSplit.setResizeEnabled(true)
        requestResponseSplit.proportion = 0.55f

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
        apiTree.selectionPath = path
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val userObject = node.userObject
        val menu = JPopupMenu()
        when (userObject) {
            is HttpApiGroup -> {
                val addGroup = JMenuItem("新建子分组")
                addGroup.addActionListener { createGroup(userObject.id) }
                menu.add(addGroup)

                val rename = JMenuItem("重命名")
                rename.addActionListener { renameGroup(userObject) }
                menu.add(rename)

                val delete = JMenuItem("删除")
                delete.addActionListener { deleteGroup(userObject) }
                menu.add(delete)
            }
            is HttpSavedRequest -> {
                val open = JMenuItem("打开")
                open.addActionListener { openRequestInTab(userObject) }
                menu.add(open)

                val rename = JMenuItem("重命名")
                rename.addActionListener { renameRequest(userObject) }
                menu.add(rename)

                val delete = JMenuItem("删除")
                delete.addActionListener { deleteRequest(userObject) }
                menu.add(delete)
            }
        }
        if (menu.componentCount > 0) {
            menu.show(apiTree, event.x, event.y)
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
        HttpApiStorage.deleteGroup(project, group.id)
        apiGroups.removeIf { it.id == group.id }
        apiRequests.removeIf { it.groupId == group.id }
        rebuildApiTree()
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
        callTabs.filter { it.savedRequestId == request.id }.forEach { tab ->
            tab.savedRequestId = null
            tab.title = buildTabTitle(tab.draft, null)
            persistTabAsync(tab)
        }
        refreshCallTabTitles()
    }

    private fun persistCurrentTab() {
        val tab = currentTab ?: return
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
        setTableEntries(pathParamsModel, tab.draft.pathParams)
        setTableEntries(paramsModel, tab.draft.params)
        setTableEntries(headersModel, tab.draft.headers)
        var bodyType = parseBodyType(tab.draft.bodyType)
        if (bodyType == HttpBodyType.NONE) {
            bodyType = when {
                tab.draft.formFields.isNotEmpty() -> HttpBodyType.FORM_DATA
                tab.draft.urlEncoded.isNotEmpty() -> HttpBodyType.FORM_URLENCODED
                !tab.draft.body.isNullOrBlank() -> HttpBodyType.JSON
                else -> HttpBodyType.NONE
            }
        }
        selectBodyType(bodyType)
        setTableEntries(urlEncodedModel, tab.draft.urlEncoded)
        setFormFields(tab.draft.formFields)
        bodyArea.text = tab.draft.body ?: ""
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
            pathParams = draft.pathParams.map { HttpKeyValue(it.key, it.value) }.toMutableList(),
            params = draft.params.map { HttpKeyValue(it.key, it.value) }.toMutableList(),
            headers = draft.headers.map { HttpKeyValue(it.key, it.value) }.toMutableList(),
            urlEncoded = draft.urlEncoded.map { HttpKeyValue(it.key, it.value) }.toMutableList(),
            formFields = draft.formFields.map { HttpFormField(it.key, it.value, it.fieldType) }.toMutableList(),
            body = draft.body
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
        val pathParams = getTableEntries(pathParamsModel)
        val params = getTableEntries(paramsModel)
        val headers = getTableEntries(headersModel)
        val bodyType = selectedBodyType()
        val body = if (bodyType == HttpBodyType.JSON) bodyArea.text.ifBlank { null } else null
        val urlEncoded = getTableEntries(urlEncodedModel)
        val formFields = getFormFields()
        return HttpRequestDraft(
            method = method,
            url = url,
            path = currentTab?.draft?.path ?: "",
            timeoutSeconds = timeoutSeconds,
            pathParams = pathParams,
            params = params,
            headers = headers,
            bodyType = bodyType.name,
            urlEncoded = urlEncoded,
            formFields = formFields,
            body = body
        )
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
        val normalizedUrl = normalizeUrl(draft.url)
        val parts = parseUrl(normalizedUrl)
        val mergedParams = mergeParams(parts.queryParams, draft.params)
        val resolvedPath = applyPathVariables(parts.path, draft.pathParams)
        val baseUrl = replacePathInBaseUrl(parts.baseUrl, parts.path, resolvedPath)
        val finalUrl = buildUrl(baseUrl, mergedParams)
        return draft.copy(
            url = finalUrl,
            path = resolvedPath,
            params = mergedParams
        )
    }

    private fun sendCurrentRequest() {
        if (isSending) {
            return
        }
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
        val runtimeDraft = resolveDraftForRequest(draft)
        if (runtimeDraft.url.isBlank()) {
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
            appendHistory(tab, runtimeDraft, error)
            return
        }
        executeRequest(tab, runtimeDraft)
    }

    private fun copyCurl() {
        val draft = resolveDraft(buildDraftFromUI())
        val runtimeDraft = resolveDraftForRequest(draft)
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
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "HTTP 请求", true) {
            override fun run(indicator: ProgressIndicator) {
                currentIndicator = indicator
                indicator.text = "发送请求..."
                var snapshot: HttpResponseSnapshot? = null
                var cookieMutations: List<CookieMutation> = emptyList()
                try {
                    val request = buildHttpRequest(draft)
                    val client = buildHttpClient(sanitizeTimeoutSeconds(draft.timeoutSeconds))
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
                    snapshot = buildResponseSnapshot(response, start)
                } catch (e: ProcessCanceledException) {
                    snapshot = HttpResponseSnapshot(
                        status = 0,
                        statusText = "已取消",
                        durationMs = elapsedMs(start),
                        sizeBytes = 0,
                        requestMethod = draft.method,
                        requestUrl = draft.url,
                        requestParams = draft.params.toMutableList(),
                        headers = mutableListOf(),
                        body = "请求已取消"
                    )
                } catch (e: CancellationException) {
                    snapshot = HttpResponseSnapshot(
                        status = 0,
                        statusText = "已取消",
                        durationMs = elapsedMs(start),
                        sizeBytes = 0,
                        requestMethod = draft.method,
                        requestUrl = draft.url,
                        requestParams = draft.params.toMutableList(),
                        headers = mutableListOf(),
                        body = "请求已取消"
                    )
                } catch (e: Exception) {
                    snapshot = HttpResponseSnapshot(
                        status = 0,
                        statusText = e.message ?: "请求失败",
                        durationMs = elapsedMs(start),
                        sizeBytes = 0,
                        requestMethod = draft.method,
                        requestUrl = draft.url,
                        requestParams = draft.params.toMutableList(),
                        headers = mutableListOf(),
                        body = e.stackTraceToString()
                    )
                } finally {
                    currentFuture = null
                    currentIndicator = null
                }
                val finalSnapshot = snapshot
                val finalCookies = cookieMutations
                SwingUtilities.invokeLater {
                    updateResponse(finalSnapshot)
                    tabResponses[tab.id] = finalSnapshot
                    appendHistory(tab, draft, finalSnapshot)
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
            responseDownloadButton.isEnabled = false
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
                responseDownloadButton.isEnabled = false
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
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
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

    private fun createKeyValuePanel(model: DefaultTableModel, emptyText: String): JPanel {
        val table = JBTable(model)
        table.emptyText.text = emptyText
        table.rowHeight = JBUI.scale(24)
        table.setShowGrid(false)
        val addButton = JButton("添加")
        val removeButton = JButton("移除")
        addButton.addActionListener {
            model.addRow(arrayOf("", ""))
            val row = model.rowCount - 1
            if (row >= 0) {
                table.editCellAt(row, 0)
            }
        }
        removeButton.addActionListener {
            val selected = table.selectedRows
            if (selected.isEmpty()) {
                if (model.rowCount > 0) {
                    model.removeRow(model.rowCount - 1)
                }
            } else {
                selected.sortedDescending().forEach { model.removeRow(it) }
            }
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        toolbar.add(addButton)
        toolbar.add(removeButton)

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        return panel
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

    private fun createCookiePanel(): JPanel {
        cookiesTable = JBTable(cookiesModel)
        cookiesTable.rowHeight = JBUI.scale(24)
        cookiesTable.setShowGrid(false)
        cookiesTable.emptyText.text = "暂无 Cookie"

        val booleanEditor = DefaultCellEditor(JComboBox(arrayOf("否", "是")))
        cookiesTable.columnModel.getColumn(5).cellEditor = booleanEditor
        cookiesTable.columnModel.getColumn(6).cellEditor = booleanEditor

        val addButton = JButton("添加")
        val removeButton = JButton("移除")
        val clearButton = JButton("清空")
        addButton.addActionListener {
            cookiesModel.addRow(arrayOf("", "", "", "/", "", "否", "否"))
            val row = cookiesModel.rowCount - 1
            if (row >= 0) {
                cookiesTable.editCellAt(row, 0)
            }
        }
        removeButton.addActionListener {
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
        clearButton.addActionListener {
            cookieEntries.clear()
            cookiesModel.setRowCount(0)
            HttpCookieStorage.clear(project)
        }

        cookiesModel.addTableModelListener {
            if (!isLoading) {
                persistCookiesFromTable()
            }
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        toolbar.add(addButton)
        toolbar.add(removeButton)
        toolbar.add(clearButton)

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(cookiesTable), BorderLayout.CENTER)
        return panel
    }

    private fun getTableEntries(model: DefaultTableModel): MutableList<HttpKeyValue> {
        val entries = mutableListOf<HttpKeyValue>()
        for (row in 0 until model.rowCount) {
            val key = (model.getValueAt(row, 0) as? String)?.trim().orEmpty()
            val value = (model.getValueAt(row, 1) as? String)?.trim().orEmpty()
            if (key.isNotBlank() || value.isNotBlank()) {
                entries.add(HttpKeyValue(key, value))
            }
        }
        return entries
    }

    private fun setTableEntries(model: DefaultTableModel, entries: List<HttpKeyValue>) {
        model.setRowCount(0)
        entries.forEach { entry ->
            model.addRow(arrayOf(entry.key, entry.value))
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
        responseDownloadButton.isEnabled = result.downloadable
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
        sendButton.isEnabled = !sending
        cancelButton.isEnabled = sending
        sendButton.text = if (sending) "发送中..." else "发送"
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
        val merged = linkedMapOf<String, String>()
        fromUrl.filter { it.key.isNotBlank() }.forEach { merged[it.key] = it.value }
        fromTable.filter { it.key.isNotBlank() }.forEach { merged[it.key] = it.value }
        return merged.map { HttpKeyValue(it.key, it.value) }.toMutableList()
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

    private fun createFormDataPanel(): JPanel {
        formDataTable = JBTable(formDataModel)
        formDataTable.rowHeight = JBUI.scale(24)
        formDataTable.setShowGrid(false)

        val typeColumn = formDataTable.columnModel.getColumn(2)
        val typeBox = JComboBox(arrayOf("文本", "文件"))
        typeColumn.cellEditor = DefaultCellEditor(typeBox)
        typeColumn.preferredWidth = JBUI.scale(80)

        val valueColumn = formDataTable.columnModel.getColumn(1)
        val defaultRenderer = formDataTable.getDefaultRenderer(Any::class.java)
        valueColumn.cellRenderer = TableCellRenderer { table, value, isSelected, hasFocus, row, column ->
            if (!isFileRow(row)) {
                return@TableCellRenderer defaultRenderer.getTableCellRendererComponent(
                    table,
                    value,
                    isSelected,
                    hasFocus,
                    row,
                    column
                )
            }
            val button = JButton(fileButtonText(value as? String))
            button.isFocusable = false
            button.toolTipText = (value as? String)?.trim().orEmpty()
            if (isSelected) {
                button.background = table.selectionBackground
                button.foreground = table.selectionForeground
            }
            button
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

        val addButton = JButton("添加")
        val removeButton = JButton("移除")

        addButton.addActionListener {
            formDataModel.addRow(arrayOf("", "", "文本"))
            val row = formDataModel.rowCount - 1
            if (row >= 0) {
                formDataTable.editCellAt(row, 0)
            }
        }
        removeButton.addActionListener {
            val selected = formDataTable.selectedRows
            if (selected.isEmpty()) {
                if (formDataModel.rowCount > 0) {
                    formDataModel.removeRow(formDataModel.rowCount - 1)
                }
            } else {
                selected.sortedDescending().forEach { formDataModel.removeRow(it) }
            }
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        toolbar.add(addButton)
        toolbar.add(removeButton)

        val panel = JPanel(BorderLayout(0, 6))
        panel.add(toolbar, BorderLayout.NORTH)
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
        private val lineMarkerEnabledBox = JBCheckBox("显示可调用图标", settings.lineMarkerEnabled)
        private val contextMenuEnabledBox = JBCheckBox("显示右键添加菜单", settings.contextMenuEnabled)

        init {
            title = "设置"
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
            return null
        }

        fun toSettings(): HttpUiSettings {
            return HttpUiSettings(
                defaultTimeoutSeconds = timeoutField.text.trim().toIntOrNull() ?: uiSettings.defaultTimeoutSeconds,
                maxRawViewChars = rawLimitField.text.trim().toIntOrNull() ?: uiSettings.maxRawViewChars,
                maxRenderChars = renderLimitField.text.trim().toIntOrNull() ?: uiSettings.maxRenderChars,
                lineMarkerEnabled = lineMarkerEnabledBox.isSelected,
                contextMenuEnabled = contextMenuEnabledBox.isSelected
            )
        }
    }

    private data class BodyTypeOption(val label: String, val type: HttpBodyType) {
        override fun toString(): String {
            return label
        }
    }

    companion object {
        private val HTTP_METHODS = arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        private val BODY_TYPES = arrayOf(
            BodyTypeOption("无", HttpBodyType.NONE),
            BodyTypeOption("JSON", HttpBodyType.JSON),
            BodyTypeOption("x-www-form-urlencoded", HttpBodyType.FORM_URLENCODED),
            BodyTypeOption("form-data", HttpBodyType.FORM_DATA)
        )
        private const val MAX_TIMEOUT_SECONDS = 120
        private const val MAX_BODY_BYTES = 1_000_000
        private const val MIN_PREVIEW_CHARS = 1000
        private const val MAX_PREVIEW_CHARS = 2_000_000
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
