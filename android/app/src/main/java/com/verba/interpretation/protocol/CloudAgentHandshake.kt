package com.verba.interpretation.protocol

import com.verba.interpretation.cloud.TranslationSessionGrant

/** Cloud-specific Agent handshake values; token remains confined to the WebSocket handshake. */
object CloudAgentHandshake {
    fun subprotocols(grant: TranslationSessionGrant): String = "translation.v1, translation.jwt.${grant.token}"

    fun startMessage(grant: TranslationSessionGrant, sourceLanguage: String, targetLanguage: String): StartMessage = StartMessage(
        sessionId = grant.sessionId,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        userId = grant.userId,
        installId = grant.installId,
    )
}
