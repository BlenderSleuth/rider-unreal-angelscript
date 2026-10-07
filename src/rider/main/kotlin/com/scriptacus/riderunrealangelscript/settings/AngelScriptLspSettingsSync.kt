package com.scriptacus.riderunrealangelscript.settings

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import org.eclipse.lsp4j.DidChangeConfigurationParams

/**
 * Pushes the current AngelScript settings to a running LSP server, e.g. after the user
 * clicks Apply in the settings page.
 *
 * The initial push on every server start (and restart) is done by AngelScriptLanguageClient,
 * which uses [buildConfigurationJson] so both paths send the same payload.
 */
class AngelScriptLspSettingsSync(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(AngelScriptLspSettingsSync::class.java)

        const val SERVER_ID = "angelscript-lsp"

        /**
         * Builds the `workspace/didChangeConfiguration` payload from the current settings.
         * The bundled server stays disconnected from Unreal until it receives this payload.
         */
        fun buildConfigurationJson(): JsonObject {
            val state = AngelScriptLspSettings.getInstance().state
            return Gson().toJsonTree(buildConfiguration(state)).asJsonObject
        }

        private fun buildConfiguration(state: AngelScriptLspSettings.State): Map<String, Any> {
            // VSCode extension expects settings under "UnrealAngelscript" key
            // The LSP server reads specific properties, so we must provide ALL of them
            // to avoid "Cannot read properties of undefined" errors
            return mapOf(
                "UnrealAngelscript" to mapOf(
                    // General
                    "unrealConnectionPort" to state.unrealConnectionPort,
                    "scriptIgnorePatterns" to state.scriptIgnorePatterns,

                    // Top-level completion settings
                    "insertParenthesisOnFunctionCompletion" to false,
                    "mathCompletionShortcuts" to state.mathCompletionShortcuts,

                    // Nested completion settings
                    "completion" to mapOf(
                        "dependencyRestrictions" to emptyList<Any>()
                    ),

                    // Diagnostics
                    "diagnosticsForUnrealNamingConvention" to state.diagnosticsForUnrealNamingConvention,
                    "markUnreadVariablesAsUnused" to false,
                    "correctFloatLiteralsWhenExpectingDoublePrecision" to state.correctFloatLiteralsWhenExpectingDoublePrecision,

                    // Inlay Hints - LSP server requires these even though Rider handles inlay hints natively
                    // We provide defaults matching VSCode extension defaults
                    "inlayHints" to mapOf(
                        "inlayHintsEnabled" to true,
                        "parameterHintsForConstants" to true,
                        "parameterHintsForComplexExpressions" to true,
                        "parameterReferenceHints" to true,
                        "parameterHintsForSingleParameterFunctions" to false,
                        "typeHintsForAutos" to true,
                        "typeHintsForAutoIgnoredTypes" to emptyList<String>(),
                        "parameterHintsIgnoredParameterNames" to listOf(
                            "Object", "Actor", "FunctionName", "Value", "InValue",
                            "NewValue", "Condition", "Parameters", "Params"
                        ),
                        "parameterHintsIgnoredFunctionNames" to emptyList<String>()
                    ),

                    // Inline Values - for debug adapter
                    "inlineValues" to mapOf(
                        "showInlineValueForFunctionThisObject" to true,
                        "showInlineValueForLocalVariables" to true,
                        "showInlineValueForParameters" to true,
                        "showInlineValueForMemberAssignment" to true
                    ),

                    // Code Lenses
                    "codeLenses" to mapOf(
                        "showCreateBlueprintClasses" to state.showCreateBlueprintClasses
                    ),

                    // Advanced
                    "projectCodeGeneration" to mapOf(
                        "enable" to state.projectCodeGenerationEnable,
                        "generators" to emptyList<Any>()
                    )
                )
            )
        }
    }

    fun syncSettingsToLsp() {
        // Only push to a server that is already running. getLanguageServer() would otherwise
        // start one; a server that starts later receives settings from AngelScriptLanguageClient.
        val manager = LanguageServerManager.getInstance(project)
        if (manager.getServerStatus(SERVER_ID) != ServerStatus.started) {
            LOG.info("AngelScript LSP server not running - settings will be sent when it starts")
            return
        }

        val config = buildConfigurationJson()
        manager.getLanguageServer(SERVER_ID).thenAccept { serverItem ->
            if (serverItem == null) {
                LOG.warn("AngelScript LSP server not available - cannot sync settings")
                return@thenAccept
            }

            try {
                serverItem.server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(config))
            } catch (e: Exception) {
                LOG.error("Failed to sync settings to LSP server", e)
            }
        }
    }
}
