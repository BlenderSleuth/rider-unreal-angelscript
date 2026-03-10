package com.scriptacus.riderunrealangelscript.lang

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.scriptacus.riderunrealangelscript.lang.psi.AngelScriptFile
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.xml.parsers.SAXParserFactory
import kotlin.concurrent.thread

/**
 * External formatter service for AngelScript language.
 */
class AngelScriptExternalFormatter : AsyncDocumentFormattingService() {

    private val log = Logger.getInstance(AngelScriptExternalFormatter::class.java)
    private val keywordsRequiringIdentifiers = listOf("private", "protected", "delegate", "event")
    private val features: Set<FormattingService.Feature> = emptySet()

    override fun canFormat(file: PsiFile): Boolean = file is AngelScriptFile

    override fun getFeatures(): Set<FormattingService.Feature> = features

    override fun getNotificationGroupId(): String = "AngelScript Formatting"

    override fun getName(): String = "AngelScript"

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask {
        val formattingContext = request.context
        val fileText = request.documentText
        val virtualFile = formattingContext.virtualFile ?: formattingContext.containingFile.virtualFile
        val assumeFileName = virtualFile?.path ?: formattingContext.containingFile.name
        val styleFile = resolveStyleFilePath(virtualFile?.path)
        val formattingRange = resolveFormattingRange(request.formattingRanges, fileText.length)

        val args = mutableListOf(
            "clang-format",
            "-output-replacements-xml",
            "-style=file:$styleFile",
            "-assume-filename=$assumeFileName"
        )

        if (formattingRange.startOffset != 0 || formattingRange.endOffset != fileText.length) {
            val byteOffset = toUtf8Bytes(fileText, 0, formattingRange.startOffset)
            val byteLength = toUtf8Bytes(fileText, formattingRange.startOffset, formattingRange.endOffset)
            args += "-offset=$byteOffset"
            args += "-length=$byteLength"
        }

        val workingDirectory = virtualFile?.parent?.path ?: formattingContext.project.basePath
        return ClangFormattingTask(args, workingDirectory, fileText, formattingRange, request)
    }

    private fun resolveFormattingRange(formattingRanges: List<TextRange>, textLength: Int): TextRange {
        if (formattingRanges.isEmpty()) {
            return TextRange(0, textLength)
        }

        val start = formattingRanges.minOf { it.startOffset }.coerceIn(0, textLength)
        val end = formattingRanges.maxOf { it.endOffset }.coerceIn(start, textLength)
        return TextRange(start, end)
    }

    private inner class ClangFormattingTask(
        private val args: List<String>,
        private val workingDirectory: String?,
        private val fileText: String,
        private val formattingRange: TextRange,
        private val request: AsyncFormattingRequest
    ) : FormattingTask {
        @Volatile
        private var cancelled = false

        @Volatile
        private var process: Process? = null

        override fun run() {
            try {
                val xml = collectClangReplacementsXml()
                if (cancelled) {
                    return
                }

                val replacements = parseReplacements(xml, fileText, formattingRange)
                request.onTextReady(applyReplacements(fileText, replacements))
            } catch (e: Exception) {
                if (cancelled) {
                    return
                }

                log.warn("Failed to format AngelScript via clang-format", e)
                request.onTextReady(fileText)
            }
        }

        override fun cancel(): Boolean {
            cancelled = true
            process?.destroyForcibly()
            return true
        }

        override fun isRunUnderProgress(): Boolean = true

        private fun collectClangReplacementsXml(): String {
            val processBuilder = ProcessBuilder(args)
                .redirectInput(ProcessBuilder.Redirect.PIPE)

            if (workingDirectory != null) {
                processBuilder.directory(File(workingDirectory))
            }

            process = processBuilder.start()
            process?.outputStream?.use { out ->
                out.write(fileText.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }

            var stdout = ""
            var stderr = ""
            val outReader = thread(start = true, isDaemon = true) {
                stdout = process?.inputStream?.bufferedReader(StandardCharsets.UTF_8).use { it?.readText().orEmpty() }
            }
            val errReader = thread(start = true, isDaemon = true) {
                stderr = process?.errorStream?.bufferedReader(StandardCharsets.UTF_8).use { it?.readText().orEmpty() }
            }

            val runningProcess = process ?: throw IllegalStateException("Failed to start clang-format")
            val finished = runningProcess.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                runningProcess.destroyForcibly()
                throw IllegalStateException("clang-format timed out")
            }

            outReader.join()
            errReader.join()

            val exitCode = runningProcess.exitValue()
            if (stderr.isNotBlank()) {
                throw IllegalStateException(stderr)
            }
            if (exitCode != 0) {
                throw IllegalStateException("clang-format exited with code $exitCode")
            }

            return stdout
        }
    }

    private fun parseReplacements(xml: String, fileText: String, formattingRange: TextRange): List<Replacement> {
        val parser = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
        }.newSAXParser()

        val edits = mutableListOf<Replacement>()
        val textBytes = fileText.toByteArray(StandardCharsets.UTF_8)
        var cacheByteOffset = 0
        var cacheCharOffset = 0

        fun byteOffsetToCharOffset(byteOffset: Int): Int {
            if (byteOffset >= cacheByteOffset) {
                val chunk = String(textBytes, cacheByteOffset, byteOffset - cacheByteOffset, StandardCharsets.UTF_8)
                cacheCharOffset += chunk.length
                cacheByteOffset = byteOffset
                return cacheCharOffset
            }

            val chunk = String(textBytes, 0, byteOffset, StandardCharsets.UTF_8)
            cacheByteOffset = byteOffset
            cacheCharOffset = chunk.length
            return cacheCharOffset
        }

        fun byteLengthToCharLength(byteOffset: Int, byteLength: Int): Int {
            return String(textBytes, byteOffset, byteLength, StandardCharsets.UTF_8).length
        }

        data class PendingEdit(
            var offsetBytes: Int,
            var lengthBytes: Int,
            val text: StringBuilder = StringBuilder()
        )

        var current: PendingEdit? = null

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                when (qName) {
                    "replacements" -> Unit
                    "replacement" -> {
                        if (current != null) {
                            throw IllegalStateException("Malformed clang-format XML output")
                        }
                        current = PendingEdit(
                            offsetBytes = attributes.getValue("offset")?.toInt()
                                ?: throw IllegalStateException("Missing replacement offset"),
                            lengthBytes = attributes.getValue("length")?.toInt()
                                ?: throw IllegalStateException("Missing replacement length")
                        )
                    }

                    else -> throw IllegalStateException("Unexpected tag <$qName>")
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                current?.text?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (qName != "replacement") {
                    return
                }

                val edit = current ?: return
                val charOffset = byteOffsetToCharOffset(edit.offsetBytes)
                val charLength = byteLengthToCharLength(edit.offsetBytes, edit.lengthBytes)
                val replacement = Replacement(charOffset, charLength, edit.text.toString())

                if (allowEdit(fileText, replacement, formattingRange)) {
                    edits += replacement
                }

                current = null
            }
        }

        parser.parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), handler)
        return edits
    }

    private fun allowEdit(fileText: String, edit: Replacement, formattingRange: TextRange): Boolean {
        val startOffset = edit.offset.coerceIn(0, fileText.length)
        val endOffset = (edit.offset + edit.length).coerceIn(startOffset, fileText.length)

        if (!formattingRange.containsRange(startOffset, endOffset)) {
            return false
        }

        val lineStart = fileText.lastIndexOf('\n', (startOffset - 1).coerceAtLeast(0)).let {
            if (it == -1) 0 else it + 1
        }
        val lineEndExclusive = fileText.indexOf('\n', startOffset).let {
            if (it == -1) fileText.length else it
        }

        val startLineText = fileText.substring(lineStart, lineEndExclusive).removeSuffix("\r")
        val startCharInLine = (startOffset - lineStart).coerceIn(0, startLineText.length)
        val endCharInLine = (endOffset - lineStart).coerceIn(startCharInLine, startLineText.length)

        val isSingleLine = startOffset <= endOffset && fileText.substring(startOffset, endOffset).none { it == '\n' }
        val startLineTextEdited = buildString {
            append(startLineText.substring(0, startCharInLine))
            append(edit.text)
            append(startLineText.substring(endCharInLine))
        }

        if (isSingleLine) {
            if (edit.length == 0 && edit.text == " ") {
                val testStart = (startOffset - 1).coerceAtLeast(0)
                val text = fileText.substring(testStart, lineEndExclusive)

                if (Regex("^[A-z]['\"]").containsMatchIn(text)) {
                    return false
                }
                if (Regex("^&(in|out)").containsMatchIn(text)) {
                    return false
                }
            }

            if (Regex("^\\s*([A-z_])+\\s+([A-z0-9_])+\\s*\\(").containsMatchIn(startLineText) &&
                Regex("^\\s*([A-z_])+\\r?\\n\\s*([A-z0-9_])+\\s*\\(").containsMatchIn(startLineTextEdited) &&
                edit.text.contains('\n')
            ) {
                return false
            }

            for (keyword in keywordsRequiringIdentifiers) {
                val original = Regex("^\\s*$keyword\\s+\\w+")
                val brokenKeyword = Regex("^\\s*$keyword\\r?\\n")
                val brokenIdentifier = Regex("^\\s*$keyword\\s+\\w+\\r?\\n")
                if (original.containsMatchIn(startLineText) &&
                    (brokenKeyword.containsMatchIn(startLineTextEdited) || brokenIdentifier.containsMatchIn(startLineTextEdited))
                ) {
                    return false
                }
            }
        }

        if (Regex("^\\s*access\\s*:").containsMatchIn(startLineText.trim())) {
            return false
        }

        return true
    }

    internal fun allowEditForTest(
        fileText: String,
        offset: Int,
        length: Int,
        replacementText: String,
        formattingRange: TextRange
    ): Boolean {
        return allowEdit(fileText, Replacement(offset, length, replacementText), formattingRange)
    }

    private fun toUtf8Bytes(text: String, startOffset: Int, endOffset: Int): Int {
        return text.substring(startOffset, endOffset).toByteArray(StandardCharsets.UTF_8).size
    }

    private fun resolveStyleFilePath(virtualFilePath: String?): String {
        val scriptRoot = resolveAngelScriptWorkspaceRoot(virtualFilePath)
        return if (scriptRoot != null) {
            File(scriptRoot, ".clang-format").path
        } else {
            ".clang-format"
        }
    }

    private fun resolveAngelScriptWorkspaceRoot(virtualFilePath: String?): File? {
        val path = virtualFilePath ?: return null
        var current = File(path).parentFile
        var projectScriptRoot: File? = null

        while (current != null) {
            if (current.name.equals("Script", ignoreCase = true)) {
                val pluginsDir = current.parentFile?.parentFile
                if (pluginsDir?.name.equals("Plugins", ignoreCase = true)) {
                    return current
                }
                projectScriptRoot = current
            }
            current = current.parentFile
        }

        return projectScriptRoot
    }

    internal fun resolveStyleFilePathForTest(virtualFilePath: String?): String {
        return resolveStyleFilePath(virtualFilePath)
    }

    internal fun applyReplacementsForTest(
        fileText: String,
        replacements: List<Triple<Int, Int, String>>
    ): String {
        return applyReplacements(fileText, replacements.map { (offset, length, text) -> Replacement(offset, length, text) })
    }

    private fun applyReplacements(fileText: String, replacements: List<Replacement>): String {
        if (replacements.isEmpty()) {
            return fileText
        }

        val result = StringBuilder(fileText)
        replacements.asReversed().forEach { replacement ->
            val start = replacement.offset.coerceIn(0, result.length)
            val end = (replacement.offset + replacement.length).coerceIn(start, result.length)
            result.replace(start, end, replacement.text)
        }
        return result.toString()
    }

    private data class Replacement(
        val offset: Int,
        val length: Int,
        val text: String
    )
}
