package com.lhstack.https

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.util.Function
import java.util.function.Supplier

class HttpEndpointLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName(): String {
        return "HTTP Request"
    }

    override fun getIcon() = HttpIcons.callGutter

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!HttpUiSettingsStore.isLineMarkerEnabled(element.project)) {
            return null
        }
        val endpoint = HttpEndpointResolver.findEndpoint(element) ?: return null
        val tooltip = endpoint.displayName
        val handler = GutterIconNavigationHandler<PsiElement> { _, elt ->
            val project = elt.project
            val draft = HttpRequestSampleBuilder.build(project, endpoint)
            HttpPluginContext.addSample(project, draft, endpoint.displayName)
            HttpPluginContext.openPanel(project)
        }
        return LineMarkerInfo(
            endpoint.anchor,
            endpoint.anchor.textRange,
            icon,
            Function<PsiElement, String> { tooltip },
            handler,
            GutterIconRenderer.Alignment.LEFT,
            Supplier { tooltip }
        )
    }
}
