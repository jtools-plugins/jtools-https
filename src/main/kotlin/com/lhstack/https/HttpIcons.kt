package com.lhstack.https

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object HttpIcons {
    val plugin: Icon = IconLoader.getIcon("/icons/http_client.svg", HttpIcons::class.java)
    val pluginTab: Icon = IconLoader.getIcon("/icons/http_client_tab.svg", HttpIcons::class.java)
    val callGutter: Icon = IconLoader.getIcon("/icons/http_call.svg", HttpIcons::class.java)
}
