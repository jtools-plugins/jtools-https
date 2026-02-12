package com.lhstack.https.component

import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.ui.LanguageTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

class MultiLanguageTextField(
    private val fileType: FileType,
    private val project: Project,
    initialText: String = "",
    private val isViewer: Boolean = false
) : JPanel(BorderLayout()), Disposable {

    private val languageTextField: LanguageTextField

    var text: String
        get() = languageTextField.text
        set(value) {
            languageTextField.text = value
        }

    val document get() = languageTextField.document
    val editor get() = languageTextField.editor

    fun setTextAndReformat(value: String) {
        languageTextField.text = value
        reformat()
    }

    fun reformat() {
        val document = languageTextField.document
        val manager = PsiDocumentManager.getInstance(project)
        val psiFile = manager.getPsiFile(document) ?: return
        val task = Runnable {
            WriteCommandAction.runWriteCommandAction(project) {
                manager.commitDocument(document)
                runCatching { CodeStyleManager.getInstance(project).reformat(psiFile) }
                manager.commitDocument(document)
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            task.run()
        } else {
            ApplicationManager.getApplication().invokeLater(task)
        }
    }

    init {
        border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)

        val language = (fileType as? LanguageFileType)?.language ?: Language.ANY
        languageTextField = object : LanguageTextField(language, project, initialText, false) {
            override fun createEditor(): EditorEx {
                val editor = super.createEditor()
                configureEditor(editor)
                return editor
            }
        }

        add(languageTextField, BorderLayout.CENTER)
    }

    private fun configureEditor(editor: EditorEx) {
        editor.isViewer = isViewer
        editor.settings.apply {
            isLineNumbersShown = true
            isWhitespacesShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            additionalLinesCount = 1
            additionalColumnsCount = 1
            isUseSoftWraps = true
            isCaretRowShown = true
            isRightMarginShown = false
            isShowIntentionBulb = false
        }

        editor.colorsScheme = EditorColorsManager.getInstance().globalScheme
        editor.setVerticalScrollbarVisible(true)
        editor.setHorizontalScrollbarVisible(true)
        editor.setBorder(JBUI.Borders.empty(2))
        editor.backgroundColor = EditorColorsManager.getInstance().globalScheme.defaultBackground
    }

    override fun dispose() {
        languageTextField.editor?.let { EditorFactory.getInstance().releaseEditor(it) }
    }
}
