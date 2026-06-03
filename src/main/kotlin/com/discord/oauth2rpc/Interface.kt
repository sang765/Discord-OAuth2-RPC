package com.discord.oauth2rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("token_type") val tokenType: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_token") val refreshToken: String,
    val scope: String,
    @SerialName("id_token") val idToken: String? = null
)

data class SessionState(
    val sessionId: String,
    val seq: Int,
    val resumeGatewayUrl: String
)

data class IdentifyPayload(
    val capabilities: Int? = null,
    val intents: Int? = null,
    val properties: Map<String, Any>? = null,
    val extra: Map<String, Any> = emptyMap()
)

data class GatewayConnectOptions(
    val token: String,
    val identify: IdentifyPayload? = null,
    val session: SessionState? = null,
    val gatewayUrl: String? = null,
    val version: Int? = null,
    val wsHeaders: Map<String, String>? = null,
    val helloTimeoutMs: Long? = null,
    val signal: Nothing? = null
)

data class GatewayPacket(
    val op: Int,
    val d: Any?,
    val s: Int?,
    val t: String?
)

data class GatewayCloseInfo(
    val code: Int,
    val reason: String,
    val resumable: Boolean,
    val session: SessionState?
)

data class SessionUpdateEvent(
    val sessionId: String?,
    val seq: Int,
    val resumeGatewayUrl: String?
)

data class ReadyEvent(
    val user: ReadyUser,
    @SerialName("session_id") val sessionId: String,
    @SerialName("resume_gateway_url") val resumeGatewayUrl: String
)

@Serializable
data class ReadyUser(
    val id: String,
    val username: String,
    @SerialName("global_name") val globalName: String? = null
)
