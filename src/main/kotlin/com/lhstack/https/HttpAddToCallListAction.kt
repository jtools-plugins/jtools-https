package com.lhstack.https

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.lhstack.tools.plugins.Helper
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElementOfExpectedTypes
import java.awt.event.MouseEvent

class HttpAddToCallListAction : AnAction(
    "添加到调用列表",
    "添加到 HTTP 调用列表",
    HttpIcons.callGutter
) {
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !HttpUiSettingsStore.isContextMenuEnabled(project)) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val endpoint = ReadAction.compute<EndpointInfo?, RuntimeException> {
            findEndpoint(e)
        }
        e.presentation.isEnabledAndVisible = endpoint != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val endpoint = ReadAction.compute<EndpointInfo?, RuntimeException> {
            findEndpoint(e)
        } ?: return
        val project = e.project ?: return
        val draft = HttpRequestSampleBuilder.build(project, endpoint)
        HttpPluginContext.addSample(project, draft, endpoint.displayName)
        HttpPluginContext.openPanel(project)
    }

    private fun findEndpoint(e: AnActionEvent): EndpointInfo? {
        val project = e.project ?: return null
        val editor = e.getData(LangDataKeys.EDITOR) ?: return null
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        if (psiFile is PsiJavaFile) {
            val element = psiFile.findElementAt(editor.caretModel.offset)
            val psiMethod = PsiTreeUtil.getContextOfType(element, PsiMethod::class.java) ?: return null
            return HttpEndpointResolver.findEndpoint(psiMethod)?.let { return it }
        }
        if (psiFile is KtFile) {
            val element = psiFile.findElementAt(editor.caretModel.offset)
            val ktFunction = PsiTreeUtil.getContextOfType(element, KtFunction::class.java) ?: return null
            val psiClass =
                ktFunction.containingClass()?.fqName?.toString()?.let { classname ->
                    JavaPsiFacade.getInstance(project)
                        .findClass(classname, GlobalSearchScope.projectScope(project))
                } ?: return null
            val psiMethod = psiClass.allMethods.firstOrNull { m ->
                ktFunction.textRange?.intersects(m.textRange!!) == true
            } ?: return null
            return HttpEndpointResolver.findEndpoint(psiMethod)?.let { return it }
        }
        return null
    }
}
