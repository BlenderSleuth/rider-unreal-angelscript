package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.scriptacus.riderunrealangelscript.settings.AngelScriptLspSettingsSync

/**
 * Custom language client for AngelScript.
 *
 * The bundled server does not connect to Unreal (and so resolves no types) until it receives
 * `workspace/didChangeConfiguration`. Sending it here on every `started` transition covers
 * lazy startup and restarts, not just project open.
 */
class AngelScriptLanguageClient(project: Project) : LanguageClientImpl(project) {

    override fun createSettings(): Any = AngelScriptLspSettingsSync.buildConfigurationJson()

    override fun handleServerStatusChanged(serverStatus: ServerStatus) {
        if (serverStatus == ServerStatus.started) {
            triggerChangeConfiguration()
        }
    }
}
