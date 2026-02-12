package com.lhstack.https

import com.intellij.json.JsonFileType
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.HtmlPanel
import com.intellij.util.ui.JBUI
import com.lhstack.https.component.MultiLanguageTextField
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

class HttpHistoryDialog(
    private val project: Project,
    titleText: String,
    entries: List<HttpRequestHistoryEntry>,
    private val onAddToCallList: (HttpRequestHistoryEntry) -> Unit,
    private val onDeleteEntry: (HttpRequestHistoryEntry) -> Unit,
    private val onClearAll: () -> Unit
) : DialogWrapper(project) {
    private val historyModel = DefaultListModel<HttpRequestHistoryEntry>()
    private val historyList = JBList(historyModel)
    private val detailPanel = HistoryDetailPanel(project)

    init {
        title = titleText
        entries.forEach { historyModel.addElement(it) }
        init()
        if (historyModel.size > 0) {
            historyList.selectedIndex = 0
        }
    }

    override fun createCenterPanel(): JPanel {
        historyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        historyList.cellRenderer = HistoryCellRenderer()
        historyList.addListSelectionListener {
            if (it.valueIsAdjusting) {
                return@addListSelectionListener
            }
            val entry = historyList.selectedValue ?: return@addListSelectionListener
            detailPanel.showEntry(entry)
        }
        historyList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showHistoryPopup(e)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showHistoryPopup(e)
                }
            }
        })

        val clearAction = object : AnAction("清空", "清空历史记录", HttpIcons.clear) {
            override fun actionPerformed(e: AnActionEvent) {
                val confirm = Messages.showYesNoDialog(
                    project,
                    "确定清空历史记录？",
                    "清空历史",
                    null
                )
                if (confirm != Messages.YES) {
                    return
                }
                onClearAll()
                historyModel.clear()
                historyList.clearSelection()
                detailPanel.clearEntry()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("HttpHistoryToolbar", DefaultActionGroup(clearAction), true)
        toolbar.component.border = JBUI.Borders.empty(0, 2)
        toolbar.targetComponent = historyList

        val leftPanel = JPanel(BorderLayout())
        leftPanel.add(toolbar.component, BorderLayout.NORTH)
        leftPanel.add(JBScrollPane(historyList), BorderLayout.CENTER)
        leftPanel.preferredSize = JBUI.size(280, 400)

        val splitPane = com.intellij.ui.JBSplitter(false, 0.3f)
        splitPane.firstComponent = leftPanel
        splitPane.secondComponent = detailPanel
        splitPane.setDividerWidth(JBUI.scale(6))
        splitPane.setShowDividerControls(true)
        splitPane.setShowDividerIcon(true)
        splitPane.setResizeEnabled(true)
        splitPane.proportion = 0.3f

        val panel = JPanel(BorderLayout())
        panel.add(splitPane, BorderLayout.CENTER)
        return panel
    }

    private fun showHistoryPopup(event: MouseEvent) {
        val index = historyList.locationToIndex(event.point)
        if (index < 0) {
            return
        }
        val bounds = historyList.getCellBounds(index, index)
        if (bounds == null || !bounds.contains(event.point)) {
            return
        }
        historyList.selectedIndex = index
        val entry = historyList.selectedValue ?: return
        val menu = JPopupMenu()
        val addItem = JMenuItem("添加到调用列表")
        addItem.addActionListener { onAddToCallList(entry) }
        menu.add(addItem)
        val deleteItem = JMenuItem("删除")
        deleteItem.addActionListener { deleteEntry(entry) }
        menu.add(deleteItem)
        menu.show(historyList, event.x, event.y)
    }

    private fun deleteEntry(entry: HttpRequestHistoryEntry) {
        val confirm = Messages.showYesNoDialog(
            project,
            "确定删除该条历史记录？",
            "删除历史",
            null
        )
        if (confirm != Messages.YES) {
            return
        }
        onDeleteEntry(entry)
        val index = historyList.selectedIndex
        historyModel.removeElement(entry)
        if (historyModel.size > 0) {
            val nextIndex = index.coerceAtMost(historyModel.size - 1)
            historyList.selectedIndex = nextIndex
        } else {
            historyList.clearSelection()
            detailPanel.clearEntry()
        }
    }

    private inner class HistoryCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val entry = value as? HttpRequestHistoryEntry
            if (entry == null) {
                text = ""
                toolTipText = null
                return component
            }
            val status = entry.response?.status?.takeIf { it > 0 }?.toString() ?: "错误"
            val label = "${entry.request.method} ${entry.request.url} | $status"
            text = StringUtil.shortenTextWithEllipsis(label, 120, 0)
            toolTipText = label
            return component
        }
    }

    private class HistoryDetailPanel(
        private val project: Project
    ) : JPanel(BorderLayout(0, 6)) {
        init {
            preferredSize = JBUI.size(720, 520)
        }
        private val methodField = JBTextField()
        private val urlField = JBTextField()
        private val pathParamsModel = DefaultTableModel(arrayOf("键", "值"), 0)
        private val paramsModel = DefaultTableModel(arrayOf("键", "值"), 0)
        private val headersModel = DefaultTableModel(arrayOf("键", "值"), 0)
        private val bodyField = MultiLanguageTextField(JsonFileType.INSTANCE, project, isViewer = true)
        private val urlEncodedModel = DefaultTableModel(arrayOf("键", "值"), 0)
        private val formDataModel = DefaultTableModel(arrayOf("键", "值", "类型"), 0)
        private val responseStatus = JBLabel("暂无响应")
        private val responseRawField = MultiLanguageTextField(PlainTextFileType.INSTANCE, project, isViewer = true)
        private val responseRenderField = MultiLanguageTextField(PlainTextFileType.INSTANCE, project, isViewer = true)
        private val responseRenderJsonField = MultiLanguageTextField(JsonFileType.INSTANCE, project, isViewer = true)
        private val responseRenderXmlField = MultiLanguageTextField(XmlFileType.INSTANCE, project, isViewer = true)
        private val responseJsonField = MultiLanguageTextField(JsonFileType.INSTANCE, project, isViewer = true)
        private val responseHeadersModel = DefaultTableModel(arrayOf("键", "值"), 0)
        private val responseRequestHeadersModel = DefaultTableModel(arrayOf("键", "值"), 0)
        private val responseRequestInfoField = MultiLanguageTextField(PlainTextFileType.INSTANCE, project, isViewer = true)
        private val responseRenderLayout = CardLayout()
        private val responseRenderPanel = JPanel(responseRenderLayout)
        private val responseRenderHtml = ResponseHtmlPanel()
        private val responseRenderImage = JBLabel()
        private val responseRenderInfo = JBLabel()

        init {
            methodField.isEditable = false
            urlField.isEditable = false
            val head = JPanel(BorderLayout(6, 0))
            head.add(methodField, BorderLayout.WEST)
            head.add(urlField, BorderLayout.CENTER)

            val requestTabs = JBTabbedPane()
            requestTabs.addTab("路径变量", createReadOnlyTablePanel(pathParamsModel))
            requestTabs.addTab("参数", createReadOnlyTablePanel(paramsModel))
            requestTabs.addTab("请求头", createReadOnlyTablePanel(headersModel))
            requestTabs.addTab("请求体", bodyField)
            requestTabs.addTab("表单参数", createReadOnlyTablePanel(urlEncodedModel))
            requestTabs.addTab("form-data", createReadOnlyTablePanel(formDataModel))

            responseRenderPanel.add(responseRenderField, "text")
            responseRenderPanel.add(responseRenderJsonField, "json")
            responseRenderPanel.add(responseRenderXmlField, "xml")
            responseRenderPanel.add(JBScrollPane(responseRenderHtml), "html")
            val imagePanel = JPanel(BorderLayout())
            imagePanel.add(responseRenderImage, BorderLayout.CENTER)
            responseRenderPanel.add(JBScrollPane(imagePanel), "image")
            val binaryPanel = JPanel(BorderLayout())
            binaryPanel.add(responseRenderInfo, BorderLayout.NORTH)
            responseRenderPanel.add(binaryPanel, "binary")

            val responseTabs = JBTabbedPane()
            responseTabs.addTab("原始", responseRawField)
            responseTabs.addTab("渲染", responseRenderPanel)
            responseTabs.addTab("JSON", responseJsonField)
            responseTabs.addTab("响应头", createReadOnlyTablePanel(responseHeadersModel))
            responseTabs.addTab("请求头", createReadOnlyTablePanel(responseRequestHeadersModel))
            responseTabs.addTab("请求信息", responseRequestInfoField)

            val responsePanel = JPanel(BorderLayout(0, 6))
            responsePanel.add(responseStatus, BorderLayout.NORTH)
            responsePanel.add(responseTabs, BorderLayout.CENTER)

            val split = com.intellij.ui.JBSplitter(true, 0.55f)
            split.firstComponent = requestTabs
            split.secondComponent = responsePanel
            split.setDividerWidth(JBUI.scale(6))
            split.setShowDividerControls(true)
            split.setShowDividerIcon(true)
            split.setResizeEnabled(true)
            split.proportion = 0.55f

            add(head, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
        }

        fun clearEntry() {
            methodField.text = ""
            urlField.text = ""
            setTableEntries(pathParamsModel, emptyList())
            setTableEntries(paramsModel, emptyList())
            setTableEntries(headersModel, emptyList())
            bodyField.text = ""
            setTableEntries(urlEncodedModel, emptyList())
            setFormFields(formDataModel, emptyList())
            clearResponse()
        }

        fun showEntry(entry: HttpRequestHistoryEntry) {
            methodField.text = entry.request.method
            urlField.text = entry.request.url
            setTableEntries(pathParamsModel, entry.request.pathParams)
            setTableEntries(paramsModel, entry.request.params)
            setTableEntries(headersModel, entry.request.headers)
            bodyField.text = entry.request.body ?: ""
            setTableEntries(urlEncodedModel, entry.request.urlEncoded)
            setFormFields(formDataModel, entry.request.formFields)
            val response = entry.response
            if (response == null) {
                clearResponse()
                return
            }
            val statusLabel = if (response.status > 0) "状态 ${response.status}" else response.statusText.ifBlank { "错误" }
            responseStatus.text = statusLabel
            setTableEntries(responseHeadersModel, response.headers)
            val requestHeaders = if (response.requestHeaders.isNotEmpty()) response.requestHeaders else entry.request.headers
            setTableEntries(responseRequestHeadersModel, requestHeaders)
            responseRawField.text = buildRawText(response)
            applyJson(response)
            responseRequestInfoField.text = buildRequestInfo(entry, response)
            applyRender(response)
        }

        private fun clearResponse() {
            responseStatus.text = "暂无响应"
            setTableEntries(responseHeadersModel, emptyList())
            setTableEntries(responseRequestHeadersModel, emptyList())
            responseRawField.text = ""
            responseRenderField.text = ""
            responseRenderJsonField.text = ""
            responseRenderXmlField.text = ""
            responseJsonField.text = ""
            responseRequestInfoField.text = ""
            responseRenderHtml.setHtml("")
            responseRenderImage.icon = null
            responseRenderInfo.text = ""
            responseRenderLayout.show(responseRenderPanel, "text")
        }

        private fun createReadOnlyTablePanel(model: DefaultTableModel): JPanel {
            val table = JBTable(model)
            table.rowHeight = JBUI.scale(24)
            table.setShowGrid(false)
            table.isEnabled = false
            table.setDefaultEditor(Any::class.java, null)
            val panel = JPanel(BorderLayout())
            panel.add(JBScrollPane(table), BorderLayout.CENTER)
            return panel
        }

        private fun setTableEntries(model: DefaultTableModel, entries: List<HttpKeyValue>) {
            model.setRowCount(0)
            entries.filter { it.key.isNotBlank() }.forEach { entry ->
                model.addRow(arrayOf(entry.key, entry.value))
            }
        }

        private fun setFormFields(model: DefaultTableModel, entries: List<HttpFormField>) {
            model.setRowCount(0)
            entries.filter { it.key.isNotBlank() }.forEach { entry ->
                model.addRow(arrayOf(entry.key, entry.value, entry.fieldType))
            }
        }

        private fun buildRawText(response: HttpResponseSnapshot): String {
            if (response.encodingUnsupported) {
                val encoding = response.contentEncoding.ifBlank { "unknown" }
                val base64 = response.bodyBase64
                val header = "内容使用不支持的编码($encoding)"
                return if (base64.isNullOrBlank()) header else "$header\nBASE64:\n$base64"
            }
            val body = response.body.orEmpty()
            if (body.isNotBlank()) {
                return appendTruncated(body, response)
            }
            val base64 = response.bodyBase64
            if (!base64.isNullOrBlank()) {
                return appendTruncated("BASE64:\n$base64", response)
            }
            return if (response.bodyTruncated) "内容过大，已截断显示" else ""
        }

        private fun applyJson(response: HttpResponseSnapshot) {
            if (response.encodingUnsupported) {
                responseJsonField.text = buildRawText(response)
                return
            }
            val body = response.body.orEmpty()
            if (body.isBlank()) {
                val base64 = response.bodyBase64
                responseJsonField.text = if (!base64.isNullOrBlank()) "BASE64:\n$base64" else ""
                return
            }
            if (!response.bodyTruncated && isJsonContent(response.contentType, body.trimStart())) {
                responseJsonField.setTextAndReformat(body)
            } else {
                responseJsonField.text = appendTruncated(body, response)
            }
        }

        private fun applyRender(response: HttpResponseSnapshot) {
            responseRenderImage.icon = null
            responseRenderInfo.text = ""
            if (response.encodingUnsupported) {
                val encoding = response.contentEncoding.ifBlank { "unknown" }
                responseRenderInfo.text = "内容使用不支持的编码($encoding)"
                responseRenderLayout.show(responseRenderPanel, "binary")
                return
            }
            val contentType = response.contentType
            val trimmedType = contentType.substringBefore(';').trim().lowercase()
            when {
                trimmedType.startsWith("image/") -> {
                    val bytes = decodeBase64(response.bodyBase64)
                    if (bytes != null) {
                        responseRenderImage.icon = ImageIcon(bytes)
                        responseRenderLayout.show(responseRenderPanel, "image")
                    } else {
                        responseRenderInfo.text = "图片内容不可用或过大"
                        responseRenderLayout.show(responseRenderPanel, "binary")
                    }
                }
                trimmedType.contains("html") -> {
                    responseRenderHtml.setHtml(response.body.orEmpty())
                    responseRenderLayout.show(responseRenderPanel, "html")
                }
                trimmedType.contains("json") -> {
                    val body = response.body.orEmpty()
                    if (!response.bodyTruncated && body.isNotBlank()) {
                        responseRenderJsonField.setTextAndReformat(body)
                    } else {
                        responseRenderJsonField.text = appendTruncated(body, response)
                    }
                    responseRenderLayout.show(responseRenderPanel, "json")
                }
                trimmedType.contains("xml") -> {
                    val body = response.body.orEmpty()
                    if (!response.bodyTruncated && body.isNotBlank()) {
                        responseRenderXmlField.setTextAndReformat(body)
                    } else {
                        responseRenderXmlField.text = appendTruncated(body, response)
                    }
                    responseRenderLayout.show(responseRenderPanel, "xml")
                }
                else -> {
                    val body = response.body.orEmpty()
                    if (body.isNotBlank() || isTextContent(contentType)) {
                        responseRenderField.text = appendTruncated(body, response)
                        responseRenderLayout.show(responseRenderPanel, "text")
                    } else {
                        responseRenderInfo.text = "二进制内容"
                        responseRenderLayout.show(responseRenderPanel, "binary")
                    }
                }
            }
        }

        private fun appendTruncated(text: String, response: HttpResponseSnapshot): String {
            if (!response.bodyTruncated) {
                return text
            }
            if (text.isBlank()) {
                return "内容过大，已截断显示"
            }
            return "$text\n\n[内容过大，已截断显示]"
        }

        private fun buildRequestInfo(entry: HttpRequestHistoryEntry, response: HttpResponseSnapshot): String {
            val lines = mutableListOf<String>()
            val method = response.requestMethod.ifBlank { entry.request.method }
            val url = response.requestUrl.ifBlank { entry.request.url }
            if (method.isNotBlank()) {
                lines.add("方法: $method")
            }
            if (url.isNotBlank()) {
                lines.add("地址: $url")
            }
            val fallbackParams = entry.request.params
            val requestParams = when {
                response.requestParams.isNotEmpty() -> response.requestParams
                url.isNotBlank() -> runCatching {
                    parseQuery(URI(url).rawQuery)
                }.getOrNull() ?: fallbackParams
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

        private fun decode(value: String): String {
            return runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.toString()) }.getOrDefault(value)
        }

        private fun decodeBase64(value: String?): ByteArray? {
            if (value.isNullOrBlank()) {
                return null
            }
            return runCatching { Base64.getDecoder().decode(value) }.getOrNull()
        }

        private fun isJsonContent(contentType: String, body: String): Boolean {
            if (contentType.lowercase().contains("json")) {
                return true
            }
            return body.startsWith("{") || body.startsWith("[")
        }

        private fun isTextContent(contentType: String): Boolean {
            val lower = contentType.lowercase()
            return lower.startsWith("text/") ||
                lower.contains("json") ||
                lower.contains("xml") ||
                lower.contains("html") ||
                lower.contains("javascript")
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
    }
}
