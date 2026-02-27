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
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPasswordField
import javax.swing.JPanel
import javax.swing.JSeparator

class HttpSettingsConfigurable(private val project: Project) : SearchableConfigurable, Configurable.NoScroll {
    private var panel: JPanel? = null
    private var timeoutField: JBTextField? = null
    private var rawLimitField: JBTextField? = null
    private var renderLimitField: JBTextField? = null
    private var lineMarkerEnabledBox: JBCheckBox? = null
    private var contextMenuEnabledBox: JBCheckBox? = null
    private var proxyEnabledBox: JBCheckBox? = null
    private var proxyTypeBox: JComboBox<String>? = null
    private var proxyHostField: JBTextField? = null
    private var proxyPortField: JBTextField? = null
    private var proxyUsernameField: JBTextField? = null
    private var proxyPasswordField: JPasswordField? = null
    private var cachedSettings: HttpUiSettings = HttpUiSettingsStore.load(project)

    override fun getId(): String = "com.lhstack.https.settings"

    override fun getDisplayName(): String = "HTTP Client"

    override fun getPreferredFocusedComponent(): JComponent? = timeoutField

    override fun createComponent(): JComponent? {
        if (panel != null) {
            reset()
            return panel
        }
        timeoutField = JBTextField()
        rawLimitField = JBTextField()
        renderLimitField = JBTextField()
        lineMarkerEnabledBox = JBCheckBox("显示可调用图标")
        contextMenuEnabledBox = JBCheckBox("显示右键添加菜单")
        proxyEnabledBox = JBCheckBox("启用代理")
        proxyTypeBox = JComboBox(PROXY_TYPES)
        proxyHostField = JBTextField()
        proxyPortField = JBTextField()
        proxyUsernameField = JBTextField()
        proxyPasswordField = JPasswordField()
        proxyEnabledBox?.addActionListener { updateProxyFieldsEnabled() }

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

        c.gridy++
        form.add(JSeparator(), c)

        c.gridy++
        form.add(proxyEnabledBox, c)

        c.gridy++
        form.add(JBLabel("代理类型"), c)
        c.gridy++
        form.add(proxyTypeBox, c)

        c.gridy++
        form.add(JBLabel("代理地址"), c)
        c.gridy++
        form.add(proxyHostField, c)

        c.gridy++
        form.add(JBLabel("代理端口"), c)
        c.gridy++
        form.add(proxyPortField, c)

        c.gridy++
        form.add(JBLabel("代理用户名(可选)"), c)
        c.gridy++
        form.add(proxyUsernameField, c)

        c.gridy++
        form.add(JBLabel("代理密码(可选)"), c)
        c.gridy++
        form.add(proxyPasswordField, c)

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
        val settings = HttpUiSettingsStore.load(project)
        val timeout = timeoutField?.text?.trim()?.toIntOrNull()
        val rawLimit = rawLimitField?.text?.trim()?.toIntOrNull()
        val renderLimit = renderLimitField?.text?.trim()?.toIntOrNull()
        val lineMarker = lineMarkerEnabledBox?.isSelected
        val contextMenu = contextMenuEnabledBox?.isSelected
        val proxyEnabled = proxyEnabledBox?.isSelected
        val proxyType = normalizeProxyType(proxyTypeBox?.selectedItem?.toString())
        val proxyHost = proxyHostField?.text?.trim()
        val proxyPort = proxyPortField?.text?.trim()?.toIntOrNull() ?: 0
        val proxyUsername = proxyUsernameField?.text?.trim()
        val proxyPassword = proxyPasswordField?.password?.let(::String)
        return timeout != settings.defaultTimeoutSeconds ||
            rawLimit != settings.maxRawViewChars ||
            renderLimit != settings.maxRenderChars ||
            lineMarker != settings.lineMarkerEnabled ||
            contextMenu != settings.contextMenuEnabled ||
            proxyEnabled != settings.proxyEnabled ||
            proxyType != settings.proxyType ||
            proxyHost != settings.proxyHost ||
            proxyPort != settings.proxyPort ||
            proxyUsername != settings.proxyUsername ||
            proxyPassword != settings.proxyPassword
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
        val proxyEnabled = proxyEnabledBox?.isSelected == true
        val proxyHost = proxyHostField?.text?.trim().orEmpty()
        val proxyPort = proxyPortField?.text?.trim()?.toIntOrNull()
        val proxyUsername = proxyUsernameField?.text?.trim().orEmpty()
        val proxyPassword = proxyPasswordField?.password?.let(::String).orEmpty()
        if (proxyEnabled) {
            if (proxyHost.isBlank()) {
                throw ConfigurationException("启用代理后，代理地址不能为空")
            }
            if (proxyPort == null) {
                throw ConfigurationException("代理端口必须是数字")
            }
            if (proxyPort !in 1..65535) {
                throw ConfigurationException("代理端口范围 1-65535")
            }
            if ((proxyUsername.isBlank() && proxyPassword.isNotBlank()) ||
                (proxyUsername.isNotBlank() && proxyPassword.isBlank())
            ) {
                throw ConfigurationException("代理认证需要同时填写用户名和密码，或全部留空")
            }
        }

        val base = HttpUiSettingsStore.load(project)
        val updated = base.copy(
            defaultTimeoutSeconds = timeout,
            maxRawViewChars = rawLimit,
            maxRenderChars = renderLimit,
            lineMarkerEnabled = lineMarkerEnabledBox?.isSelected ?: base.lineMarkerEnabled,
            contextMenuEnabled = contextMenuEnabledBox?.isSelected ?: base.contextMenuEnabled,
            proxyEnabled = proxyEnabled,
            proxyType = normalizeProxyType(proxyTypeBox?.selectedItem?.toString()),
            proxyHost = proxyHost,
            proxyPort = proxyPort ?: 0,
            proxyUsername = proxyUsername,
            proxyPassword = proxyPassword
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
        proxyEnabledBox?.isSelected = settings.proxyEnabled
        proxyTypeBox?.selectedItem = normalizeProxyType(settings.proxyType)
        proxyHostField?.text = settings.proxyHost
        proxyPortField?.text = if (settings.proxyPort in 1..65535) settings.proxyPort.toString() else ""
        proxyUsernameField?.text = settings.proxyUsername
        proxyPasswordField?.text = settings.proxyPassword
        updateProxyFieldsEnabled()
    }

    override fun disposeUIResources() {
        panel = null
        timeoutField = null
        rawLimitField = null
        renderLimitField = null
        lineMarkerEnabledBox = null
        contextMenuEnabledBox = null
        proxyEnabledBox = null
        proxyTypeBox = null
        proxyHostField = null
        proxyPortField = null
        proxyUsernameField = null
        proxyPasswordField = null
    }

    private fun parseInt(field: JBTextField?, label: String): Int {
        val text = field?.text?.trim() ?: ""
        return text.toIntOrNull() ?: throw ConfigurationException("请输入数字: $label")
    }

    private fun updateProxyFieldsEnabled() {
        val enabled = proxyEnabledBox?.isSelected == true
        proxyTypeBox?.isEnabled = enabled
        proxyHostField?.isEnabled = enabled
        proxyPortField?.isEnabled = enabled
        proxyUsernameField?.isEnabled = enabled
        proxyPasswordField?.isEnabled = enabled
    }

    private fun normalizeProxyType(value: String?): String {
        return if (value?.trim()?.equals("SOCKS", ignoreCase = true) == true) "SOCKS" else "HTTP"
    }

    companion object {
        private val PROXY_TYPES = arrayOf("HTTP", "SOCKS")
        private const val MAX_TIMEOUT_SECONDS = 120
        private const val MIN_PREVIEW_CHARS = 1000
        private const val MAX_PREVIEW_CHARS = 2_000_000
    }
}
