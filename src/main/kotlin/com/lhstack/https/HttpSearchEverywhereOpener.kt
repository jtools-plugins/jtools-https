package com.lhstack.https

import com.intellij.ide.DataManager
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.Processor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

object HttpSearchEverywhereOpener {
    const val SEARCH_TAB_ID = "jtools.http.search"
    private val log = Logger.getInstance(HttpSearchEverywhereOpener::class.java)

    fun open(project: Project, contextComponent: JComponent? = null): Boolean {
        val dataContext = buildContext(project, contextComponent)
        val event = AnActionEvent.createFromDataContext(ActionPlaces.ACTION_SEARCH, Presentation(), dataContext)
        val manager = SearchEverywhereManager.getInstance(project)
        return runCatching {
            manager.show("", SEARCH_TAB_ID, event)
            true
        }.onFailure { error ->
            log.warn("Open JTools Http Search failed", error)
        }.recoverCatching {
            val action = ActionManager.getInstance().getAction("SearchEverywhere")
                ?: error("SearchEverywhere action not found")
            action.actionPerformed(
                AnActionEvent.createFromDataContext(ActionPlaces.ACTION_SEARCH, Presentation(), dataContext)
            )
            ApplicationManager.getApplication().invokeLater {
                runCatching { manager.selectedTabID = SEARCH_TAB_ID }
            }
            true
        }.onFailure { error ->
            log.warn("Fallback open SearchEverywhere failed", error)
        }.recoverCatching {
            openLocalSearchPopup(project, contextComponent)
        }.getOrDefault(false)
    }

    private fun buildContext(project: Project, contextComponent: JComponent?): com.intellij.openapi.actionSystem.DataContext {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        if (contextComponent != null) {
            builder.setParent(DataManager.getInstance().getDataContext(contextComponent))
            builder.add(PlatformCoreDataKeys.CONTEXT_COMPONENT, contextComponent)
        }
        return builder.build()
    }

    private fun openLocalSearchPopup(project: Project, contextComponent: JComponent?): Boolean {
        val contributor = JToolsHttpSearchContributor(project)
        val input = JBTextField().apply {
            emptyText.text = "输入接口地址 / 方法名 / 注释 / 参数 / 代码进行搜索"
        }
        val model = javax.swing.DefaultListModel<JToolsHttpSearchContributor.SearchItem>()
        val list = JBList(model).apply {
            emptyText.text = "请输入关键字搜索"
            @Suppress("UNCHECKED_CAST")
            cellRenderer = contributor.elementsRenderer as ListCellRenderer<in JToolsHttpSearchContributor.SearchItem>
            selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        }
        val searchVersion = java.util.concurrent.atomic.AtomicInteger(0)
        var popup: com.intellij.openapi.ui.popup.JBPopup? = null

        fun refresh() {
            val query = input.text?.trim().orEmpty()
            if (query.isBlank()) {
                model.clear()
                list.emptyText.text = "请输入关键字搜索"
                return
            }
            val currentVersion = searchVersion.incrementAndGet()
            model.clear()
            list.emptyText.text = "搜索中..."
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = mutableListOf<JToolsHttpSearchContributor.SearchItem>()
                val indicator = EmptyProgressIndicator()
                val consumer = Processor<JToolsHttpSearchContributor.SearchItem> { item ->
                    result.add(item)
                    result.size < 500
                }
                runCatching { contributor.fetchElements(query, indicator, consumer) }
                ApplicationManager.getApplication().invokeLater {
                    val currentPopup = popup
                    if (searchVersion.get() != currentVersion || currentPopup == null || currentPopup.isDisposed) {
                        return@invokeLater
                    }
                    model.clear()
                    result.forEach { model.addElement(it) }
                    if (model.isEmpty) {
                        list.emptyText.text = "无匹配结果"
                    }
                }
            }
        }

        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })

        val root = JPanel(BorderLayout(0, 8))
        root.border = JBUI.Borders.empty(8)
        root.preferredSize = Dimension(980, 560)
        root.add(input, BorderLayout.NORTH)
        root.add(JBScrollPane(list), BorderLayout.CENTER)

        popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(root, input)
            .setTitle("JTools Http Search")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .createPopup()

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) {
                    return
                }
                val item = list.selectedValue ?: return
                contributor.processSelectedItem(item, 0, input.text ?: "")
                popup?.cancel()
            }
        })

        input.addActionListener {
            val item = list.selectedValue ?: return@addActionListener
            contributor.processSelectedItem(item, 0, input.text ?: "")
            popup?.cancel()
        }

        if (contextComponent != null) {
            popup?.showCenteredInCurrentWindow(project)
        } else {
            popup?.showInFocusCenter()
        }
        return true
    }
}
