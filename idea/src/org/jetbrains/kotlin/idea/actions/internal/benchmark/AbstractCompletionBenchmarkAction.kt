/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.actions.internal.benchmark

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.navigation.NavigationUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.jetbrains.kotlin.idea.actions.internal.KotlinInternalMode
import org.jetbrains.kotlin.idea.caches.resolve.ModuleOrigin
import org.jetbrains.kotlin.idea.caches.resolve.getNullableModuleInfo
import org.jetbrains.kotlin.idea.completion.CompletionBenchmarkSink
import org.jetbrains.kotlin.idea.core.moveCaret
import org.jetbrains.kotlin.idea.core.util.EDT
import org.jetbrains.kotlin.idea.refactoring.getLineCount
import org.jetbrains.kotlin.idea.stubindex.KotlinExactPackagesIndex
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.psi.KtFile
import java.util.*
import javax.swing.JFileChooser
import kotlin.properties.Delegates

abstract class AbstractCompletionBenchmarkAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent?) {
        val project = e?.project ?: return

        val benchmarkSink = CompletionBenchmarkSink.enableAndGet()
        val scenario = createBenchmarkScenario(project, benchmarkSink) ?: return

        launch(EDT) {
            scenario.doBenchmark()
            CompletionBenchmarkSink.disable()
        }
    }

    internal abstract fun createBenchmarkScenario(project: Project, benchmarkSink: CompletionBenchmarkSink.Impl): AbstractCompletionBenchmarkScenario?

    companion object {
        fun showPopup(project: Project, text: String) {
            val statusBar = WindowManager.getInstance().getStatusBar(project)
            JBPopupFactory.getInstance()
                    .createHtmlTextBalloonBuilder(text, MessageType.ERROR, null)
                    .setFadeoutTime(5000)
                    .createBalloon().showInCenterOf(statusBar.component)
        }

        internal fun <T> List<T>.randomElement(random: Random): T? = if (this.isNotEmpty()) this[random.nextInt(this.size)] else null
        internal fun <T> Array<T>.randomElement(random: Random): T? = if (this.isNotEmpty()) this[random.nextInt(this.size)] else null

    }

    fun collectSuitableKotlinFiles(project: Project, filter: (KtFile) -> Boolean): MutableList<KtFile> {
        val scope = object : DelegatingGlobalSearchScope(GlobalSearchScope.allScope(project)) {
            override fun isSearchOutsideRootModel(): Boolean = false
        }
        val exactPackageIndex = KotlinExactPackagesIndex.getInstance()

        fun KtFile.isUsableForBenchmark(): Boolean {
            val moduleInfo = this.getNullableModuleInfo() ?: return false
            if (this.isCompiled || !this.isWritable || this.isScript) return false
            return moduleInfo.moduleOrigin == ModuleOrigin.MODULE
        }

        val ktFiles = mutableListOf<KtFile>()
        exactPackageIndex.processAllKeys(project) {
            exactPackageIndex.get(it, project, scope).forEach {
                if (it.isUsableForBenchmark() && filter(it)) {
                    ktFiles += it
                }
            }
            true
        }
        return ktFiles
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = KotlinInternalMode.enabled
    }
}

internal abstract class AbstractCompletionBenchmarkScenario(
        val project: Project, val benchmarkSink: CompletionBenchmarkSink.Impl,
        val random: Random = Random()) {

    protected data class Result(val lines: Int, val filePath: String, val first: Long, val full: Long)

    protected suspend fun typeAtOffsetAndGetResult(text: String, offset: Int, file: KtFile): Result {
        NavigationUtil.openFileWithPsiElement(file.navigationElement, false, true)

        val document = PsiDocumentManager.getInstance(project).getDocument(file)
        val ourEditor = EditorFactory.getInstance().allEditors.find { it.document == document }

        var result by Delegates.notNull<Result>()
        if (document != null && ourEditor != null) {

            delay(500)

            ourEditor.moveCaret(offset, scrollType = ScrollType.CENTER)

            delay(500)

            val t = text
            CommandProcessor.getInstance().executeCommand(project, {
                runWriteAction {
                    document.insertString(ourEditor.caretModel.offset, "\n$t\n")
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }
                ourEditor.moveCaret(ourEditor.caretModel.offset + t.length + 1)
                AutoPopupController.getInstance(project).scheduleAutoPopup(ourEditor, CompletionType.BASIC, null)
            }, "ss", "ss")

            result = collectResult(file, document.getLineNumber(offset))

            CommandProcessor.getInstance().executeCommand(project, {
                runWriteAction {
                    document.deleteString(offset, offset + t.length + 2)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }
            }, "ss", "ss")
            delay(100)
        }
        return result
    }

    protected suspend fun collectResult(file: KtFile, line: Int): Result {
        val results = benchmarkSink.channel.receive()
        return Result(file.getLineCount(), "${file.virtualFile.path}:$line", results.firstFlush, results.full)
    }


    protected fun saveResults(allResults: List<Result>) {
        val jfc = JFileChooser()
        val result = jfc.showSaveDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            val file = jfc.selectedFile
            file.writeText(buildString {
                appendln("n, file, lines, ff, full")
                var i = 0
                allResults.joinTo(this, separator = "\n") { (l, f, ff, lf) -> "${i++}, $f, $l, $ff, $lf" }
            })
        }
        AbstractCompletionBenchmarkAction.showPopup(project, "Done")
    }


    abstract suspend fun doBenchmark()
}