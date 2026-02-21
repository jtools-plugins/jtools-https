package com.lhstack.https

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

class HttpSettingsConfigurable(private val project: Project) : SearchableConfigurable, Configurable.NoScroll {
    private var panel: JPanel? = null
    private var timeoutField: JBTextField? = null
    private var rawLimitField: JBTextField? = null
    private var renderLimitField: JBTextField? = null
    private var lineMarkerEnabledBox: JBCheckBox? = null
    private var contextMenuEnabledBox: JBCheckBox? = null
    private var cachedSettings: HttpUiSettings = HttpUiSettingsStore.load(project)

    override fun getId(): String = "com.lhstack.https.settings"

    override fun getDisplayName(): String = "HTTP Client"

    override fun getPreferredFocusedComponent(): JComponent? = timeoutField

    override fun createComponent(): JComponent? {
        if (panel != null) {
            return panel
        }
        timeoutField = JBTextField()
        rawLimitField = JBTextField()
        renderLimitField = JBTextField()
        lineMarkerEnabledBox = JBCheckBox("显示可调用图标")
        contextMenuEnabledBox = JBCheckBox("显示右键添加菜单")

        val form = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(4)
        }

        form.add(JBLabel("默认超时(s)"), c)
        c.gridy++
        form.add(timeoutField, c)

        c.gridy++
        form.add(JBLabel("原始预览上限(字符, 0=不限)"), c)
        c.gridy++
        form.add(rawLimitField, c)

        c.gridy++
        form.add(JBLabel("渲染预览上限(字符, 0=不限)"), c)
        c.gridy++
        form.add(renderLimitField, c)

        c.gridy++
        form.add(lineMarkerEnabledBox, c)

        c.gridy++
        form.add(contextMenuEnabledBox, c)

        // Spacer to push content to the top-left when the settings panel is tall.
        c.gridy++
        c.weighty = 1.0
        c.fill = GridBagConstraints.VERTICAL
        form.add(JPanel(), c)

        panel = form
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val settings = cachedSettings
        val timeout = timeoutField?.text?.trim()?.toIntOrNull()
        val rawLimit = rawLimitField?.text?.trim()?.toIntOrNull()
        val renderLimit = renderLimitField?.text?.trim()?.toIntOrNull()
        val lineMarker = lineMarkerEnabledBox?.isSelected
        val contextMenu = contextMenuEnabledBox?.isSelected
        return timeout != settings.defaultTimeoutSeconds ||
            rawLimit != settings.maxRawViewChars ||
            renderLimit != settings.maxRenderChars ||
            lineMarker != settings.lineMarkerEnabled ||
            contextMenu != settings.contextMenuEnabled
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val timeout = parseInt(timeoutField, "默认超时(s)")
        if (timeout !in 1..MAX_TIMEOUT_SECONDS) {
            throw ConfigurationException("超时范围 1-$MAX_TIMEOUT_SECONDS")
        }
        val rawLimit = parseInt(rawLimitField, "原始预览上限")
        if (rawLimit != 0 && rawLimit !in MIN_PREVIEW_CHARS..MAX_PREVIEW_CHARS) {
            throw ConfigurationException("原始预览上限范围 0(不限) 或 $MIN_PREVIEW_CHARS-$MAX_PREVIEW_CHARS")
        }
        val renderLimit = parseInt(renderLimitField, "渲染预览上限")
        if (renderLimit != 0 && renderLimit !in MIN_PREVIEW_CHARS..MAX_PREVIEW_CHARS) {
            throw ConfigurationException("渲染预览上限范围 0(不限) 或 $MIN_PREVIEW_CHARS-$MAX_PREVIEW_CHARS")
        }

        val updated = cachedSettings.copy(
            defaultTimeoutSeconds = timeout,
            maxRawViewChars = rawLimit,
            maxRenderChars = renderLimit,
            lineMarkerEnabled = lineMarkerEnabledBox?.isSelected ?: cachedSettings.lineMarkerEnabled,
            contextMenuEnabled = contextMenuEnabledBox?.isSelected ?: cachedSettings.contextMenuEnabled
        )
        HttpPluginContext.updateSettings(project, updated)
        cachedSettings = HttpUiSettingsStore.load(project)
        reset()
    }

    override fun reset() {
        cachedSettings = HttpUiSettingsStore.load(project)
        val settings = cachedSettings
        timeoutField?.text = settings.defaultTimeoutSeconds.toString()
        rawLimitField?.text = settings.maxRawViewChars.toString()
        renderLimitField?.text = settings.maxRenderChars.toString()
        lineMarkerEnabledBox?.isSelected = settings.lineMarkerEnabled
        contextMenuEnabledBox?.isSelected = settings.contextMenuEnabled
    }

    override fun disposeUIResources() {
        panel = null
        timeoutField = null
        rawLimitField = null
        renderLimitField = null
        lineMarkerEnabledBox = null
        contextMenuEnabledBox = null
    }

    private fun parseInt(field: JBTextField?, label: String): Int {
        val text = field?.text?.trim() ?: ""
        return text.toIntOrNull() ?: throw ConfigurationException("请输入数字: $label")
    }

    companion object {
        private const val MAX_TIMEOUT_SECONDS = 120
        private const val MIN_PREVIEW_CHARS = 1000
        private const val MAX_PREVIEW_CHARS = 2_000_000
    }
}
