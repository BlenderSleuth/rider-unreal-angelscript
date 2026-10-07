package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.client.features.LSPCompletionFeature
import com.redhat.devtools.lsp4ij.client.features.LSPCompletionProposal
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/**
 * AngelScript-specific completion proposal.
 *
 * Overrides lsp4ij's `apply()` for two reasons:
 *
 * 1. **Scope-aware prefix.** When the server sends no textEdit, lsp4ij replaces from the start
 *    of the PSI element, so completing `FVector::Z` would replace `FVector::` as well.
 *    [calculateScopeAwarePrefix] treats `::` as a word boundary instead.
 * 2. **Caret placement.** Edits are applied end-to-start, preserving LSP array order for
 *    same-position edits, and the caret ends up at the end of the main textEdit rather than
 *    being shifted by additionalTextEdits (e.g. an inserted `UFUNCTION()` line).
 *
 * It also reformats the edited range after insertion (see [handleInsert]).
 *
 * Only public lsp4ij API is used. lsp4ij's private fields and methods are not accessed, so
 * newer lsp4ij releases (which users install independently from the Marketplace) cannot
 * break completion by renaming them.
 */
class AngelScriptCompletionProposal(
    item: CompletionItem,
    private val completionContext: LSPCompletionFeature.LSPCompletionContext,
    completionFeature: LSPCompletionFeature
) : LSPCompletionProposal(item, completionContext, completionFeature) {

    companion object {
        private val LOG = Logger.getInstance(AngelScriptCompletionProposal::class.java)

        /**
         * Stable comparator for TextEdits - DESCENDING order (end to start of document).
         *
         * Edits must be applied from END to START of document to avoid offset shifts.
         * When edits are at the same position, LSP array order must be preserved.
         */
        private class StableTextEditComparator : Comparator<IndexedTextEdit> {
            override fun compare(a: IndexedTextEdit, b: IndexedTextEdit): Int {
                val aStart = a.edit.range.start
                val bStart = b.edit.range.start

                // Sort by start line (DESCENDING - from end to start)
                val lineDiff = bStart.line - aStart.line
                if (lineDiff != 0) return lineDiff

                // Then by start character (DESCENDING - from end to start)
                val charDiff = bStart.character - aStart.character
                if (charDiff != 0) return charDiff

                // At same position: preserve LSP array order
                // Since we're processing in reverse, we need to REVERSE the index order too
                return b.originalIndex - a.originalIndex
            }
        }

        private data class IndexedTextEdit(
            val edit: TextEdit,
            val originalIndex: Int
        )

        /**
         * Calculate prefix start offset with AngelScript scope awareness.
         *
         * Unlike lsp4ij's default word boundary logic, this recognizes :: as a special
         * boundary for scoped identifiers. When completing "FVector::Z", this returns
         * the offset after :: (pointing at Z), not before FVector.
         *
         * lsp4ij uses PSI element text ranges to calculate prefixStartOffset, which returns
         * the start of the entire scope_resolution PSI node. For "FVector::Z", lsp4ij returns
         * the position of 'F', but we need the position of 'Z'.
         */
        private fun calculateScopeAwarePrefix(document: Document, offset: Int): Int {
            if (offset == 0) return 0

            val text = document.text
            var pos = offset - 1

            while (pos >= 0) {
                val char = text[pos]

                // Stop at scope resolution operator (::)
                if (pos > 0 && text[pos - 1] == ':' && char == ':') {
                    return pos + 1  // Prefix starts after ::
                }

                // Stop at incomplete scope operator (single :)
                if (char == ':') {
                    return pos + 1  // Prefix starts after :
                }

                // Stop at other non-identifier characters
                if (!char.isLetterOrDigit() && char != '_') {
                    return pos + 1
                }

                pos--
            }

            return 0
        }

        /**
         * Apply text edits with stable sorting.
         * This is a copy of LSPIJUtils.doApplyEdits() but with a stable comparator.
         *
         * IMPORTANT: For completion, the first edit in the list is ALWAYS the MAIN textEdit
         * that replaces the completion prefix. The caret should be positioned at the END of
         * this main edit. Additional edits (like adding imports or decorators) should NOT
         * affect caret positioning.
         */
        private fun applyEditsWithStableSort(
            editor: Editor?,
            document: Document,
            edits: List<TextEdit>
        ) {
            if (edits.isEmpty()) return

            // Index edits to track original order
            val indexedEdits = edits.mapIndexed { index, edit -> IndexedTextEdit(edit, index) }

            // Sort with stable comparator (descending - from end to start)
            val sortedIndexed = indexedEdits.sortedWith(StableTextEditComparator())
            val sortedEdits = sortedIndexed.map { it.edit }

            // Create pairs of (TextEdit, RangeMarker)
            val pairs = mutableListOf<Pair<TextEdit, RangeMarker>>()
            for (textEdit in sortedEdits) {
                val range = textEdit.range
                if (range != null) {
                    val start = LSPIJUtils.toOffset(range.start, document)
                    val end = LSPIJUtils.toOffset(range.end, document)
                    if (end >= start) {
                        val marker = document.createRangeMarker(start, end)
                        pairs.add(Pair(textEdit, marker))
                    }
                }
            }

            if (pairs.isEmpty()) return

            // Track the main textEdit (always first) to position caret at its end
            val mainTextEdit = edits.firstOrNull()
            var caretMarker: RangeMarker? = null

            // Apply each edit
            for ((edit, marker) in pairs) {
                val startOffset = marker.startOffset
                val endOffset = marker.endOffset
                val newText = edit.newText ?: ""

                document.replaceString(startOffset, endOffset, newText)

                // If this is the main textEdit, create a marker to track the caret position
                // The marker will automatically adjust as subsequent edits are applied
                if (mainTextEdit != null && edit === mainTextEdit) {
                    val caretPosition = startOffset + newText.length
                    caretMarker = document.createRangeMarker(caretPosition, caretPosition)
                }

                marker.dispose()
            }

            // Move caret to end of main textEdit (marker has been adjusted for all edits)
            if (caretMarker != null && editor != null) {
                editor.caretModel.moveToOffset(caretMarker.startOffset)
                caretMarker.dispose()
            }
        }
    }

    override fun apply(document: Document, offset: Int) {
        // Mirrors lsp4ij's LSPCompletionProposal.apply(), except for the prefix calculation
        // and applyEditsWithStableSort (see class documentation).
        // By the time apply() runs, lsp4ij's handleInsert() has already replaced `item` with the
        // resolved completion item (LSPCompletionFeature.shouldResolveOnApply defaults to true).

        var insertText: String? = null
        var textEdit: TextEdit? = null

        try {
            val eitherTextEdit = item.textEdit
            if (eitherTextEdit != null) {
                textEdit = if (eitherTextEdit.isLeft) {
                    eitherTextEdit.left
                } else {
                    val insertReplaceEdit = eitherTextEdit.right
                    TextEdit(insertReplaceEdit.insert, insertReplaceEdit.newText)
                }
            }

            if (textEdit == null) {
                insertText = item.insertText ?: item.label
                val startOffset = calculateScopeAwarePrefix(document, offset)
                val endOffset = offset
                val start = LSPIJUtils.toPosition(startOffset, document)
                val end = LSPIJUtils.toPosition(endOffset, document)
                textEdit = TextEdit(Range(start, end), insertText)
            } else {
                val completionOffset = completionContext.parameters.offset
                if (offset > completionOffset) {
                    val shift = offset - completionOffset
                    textEdit.range.end.character = textEdit.range.end.character + shift
                }
            }

            // Workaround https://github.com/Microsoft/vscode/issues/17036
            run {
                val start = textEdit.range.start
                val end = textEdit.range.end
                if (start.line > end.line || (start.line == end.line && start.character > end.character)) {
                    textEdit.range.end = start
                    textEdit.range.start = end
                }
            }

            // Allow completion items to be wrong with too wide range
            run {
                val documentEnd = LSPIJUtils.toPosition(document.textLength, document)
                val textEditEnd = textEdit.range.end
                if (documentEnd.line < textEditEnd.line ||
                    (documentEnd.line == textEditEnd.line && documentEnd.character < textEditEnd.character)) {
                    textEdit.range.end = documentEnd
                }
            }

            // Reuse existing characters
            if (insertText != null) {
                val scopeAwarePrefix = calculateScopeAwarePrefix(document, offset)
                val shift = offset - scopeAwarePrefix
                var commonSize = 0
                while (commonSize < insertText.length - shift &&
                    document.textLength > offset + commonSize &&
                    document.text[scopeAwarePrefix + shift + commonSize] == insertText[commonSize + shift]) {
                    commonSize++
                }
                textEdit.range.end.character = textEdit.range.end.character + commonSize
            }

            val additionalEdits = item.additionalTextEdits

            // Combine all edits
            val allEdits = if (additionalEdits != null && additionalEdits.isNotEmpty()) {
                val list = mutableListOf<TextEdit>()
                list.add(textEdit)
                list.addAll(additionalEdits)
                list
            } else {
                listOf(textEdit)
            }

            val editor = completionContext.parameters.editor
            WriteAction.run<RuntimeException> {
                applyEditsWithStableSort(editor, document, allEdits)
            }

        } catch (ex: RuntimeException) {
            LOG.warn(ex.localizedMessage, ex)
        }
    }

    /**
     * Override handleInsert to add auto-formatting after completion.
     * This is called by IntelliJ after the completion is inserted.
     */
    override fun handleInsert(context: InsertionContext) {
        // Let parent handle the insertion (applies edits, snippets, commands, etc.)
        super.handleInsert(context)

        // Auto-format the inserted code
        try {
            val document = context.document

            // `item` is the resolved completion item at this point (see apply())
            val additionalEdits = item.additionalTextEdits

            LOG.info("=== Auto-format after completion ===")
            LOG.info("Context startOffset: ${context.startOffset}, tailOffset: ${context.tailOffset}")
            LOG.info("Context range in document: ${LSPIJUtils.toPosition(context.startOffset, document)} - ${LSPIJUtils.toPosition(context.tailOffset, document)}")

            if (additionalEdits != null && additionalEdits.isNotEmpty()) {
                LOG.info("Additional text edits (${additionalEdits.size}):")
                additionalEdits.forEachIndexed { index, edit ->
                    LOG.info("  [$index] range: ${edit.range.start.line}:${edit.range.start.character} - ${edit.range.end.line}:${edit.range.end.character}, text: ${edit.newText.replace("\n", "\\n").replace("\t", "\\t")}")
                }
            }

            // Commit document to sync PSI with document changes
            context.commitDocument()

            val psiFile = context.file
            val project = context.project

            // Calculate the full range affected by all edits (main edit + additional edits)
            var minOffset = context.startOffset
            var maxOffset = context.tailOffset

            if (additionalEdits != null && additionalEdits.isNotEmpty()) {
                for (edit in additionalEdits) {
                    val editStart = LSPIJUtils.toOffset(edit.range.start, document)
                    val editEnd = LSPIJUtils.toOffset(edit.range.end, document)
                    minOffset = minOf(minOffset, editStart)
                    maxOffset = maxOf(maxOffset, editEnd + edit.newText.length)
                }
            }

            LOG.info("Combined edit range: $minOffset - $maxOffset")
            LOG.info("Combined edit range in document: ${LSPIJUtils.toPosition(minOffset, document)} - ${LSPIJUtils.toPosition(maxOffset, document)}")

            // Just format the full edited range - the LSP server has already provided properly formatted code
            val formatStart = minOffset
            val formatEnd = maxOffset

            LOG.info("Formatting full edited range: $formatStart - $formatEnd")
            LOG.info("Formatting range in document: ${LSPIJUtils.toPosition(formatStart, document)} - ${LSPIJUtils.toPosition(formatEnd, document)}")

            // Format the range
            WriteCommandAction.runWriteCommandAction(project) {
                CodeStyleManager.getInstance(project).reformatText(psiFile, formatStart, formatEnd)
            }
            LOG.info("=== Auto-format complete ===")
        } catch (ex: Exception) {
            LOG.warn("Failed to auto-format after completion", ex)
        }
    }
}
