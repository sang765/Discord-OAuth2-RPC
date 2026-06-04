package com.discord.oauth2rpc.utils

import com.discord.oauth2rpc.API
import com.discord.oauth2rpc.JsonObjectMapper
import com.discord.oauth2rpc.TokenResponse
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

typealias Snowflake = String

data class APIEmoji(val animated: Boolean, val name: String, val id: String?)
data class RawEmoji(val id: String?, val name: String? = null, val animated: Boolean = false)

sealed class EmojiIdentifierResolvable {
    data class StringVal(val value: String) : EmojiIdentifierResolvable()
    data class ObjectVal(val id: String? = null, val name: String? = null, val animated: Boolean = false) : EmojiIdentifierResolvable()
}

object Util {

    fun flatten(obj: Any?, vararg props: Map<String, Any>): Map<String, Any?> {
        if (obj !is Map<*, *>) return mapOf("value" to obj)
        val mergedProps = mutableMapOf<String, Boolean>()
        for (k in obj.keys) {
            if (k is String && !k.startsWith("_")) mergedProps[k] = true
        }
        for (p in props) {
            for ((k, v) in p) mergedProps[k] = v == true || v == "true"
        }
        val out = mutableMapOf<String, Any?>()
        for ((prop, _) in mergedProps) {
            val element = (obj as Map<*, *>)[prop]
            when {
                element is List<*> -> out[prop] = element.map { e -> if (e is Map<*, *>) flatten(e) else e }
                element is JsonElement -> out[prop] = element.toString()
                element is Map<*, *> -> out[prop] = flatten(element)
                else -> out[prop] = element
            }
        }
        return out
    }

    fun parseEmoji(text: String): APIEmoji? {
        val decoded = if (text.contains("%")) URLDecoder.decode(text, "UTF-8") else text
        if (!decoded.contains(":")) return APIEmoji(animated = false, name = decoded, id = null)
        val match = Regex("<?(?:(a):)?(\\w{2,32}):(\\d{17,19})?>?").find(decoded)
        return match?.let { APIEmoji(animated = it.groupValues[1] == "a", name = it.groupValues[2], id = it.groupValues[3]) }
    }

    fun resolvePartialEmoji(emoji: EmojiIdentifierResolvable?): RawEmoji? {
        if (emoji == null) return null
        return when (emoji) {
            is EmojiIdentifierResolvable.StringVal -> {
                if (Regex("^\\d{17,19}$").matches(emoji.value)) RawEmoji(id = emoji.value)
                else parseEmoji(emoji.value)?.let { RawEmoji(id = it.id, name = it.name, animated = it.animated) }
            }
            is EmojiIdentifierResolvable.ObjectVal -> {
                if (emoji.id == null && emoji.name == null) null
                else RawEmoji(id = emoji.id, name = emoji.name, animated = emoji.animated)
            }
        }
    }

    fun calculateUserDefaultAvatarIndex(userId: Snowflake): Int {
        val id = userId.toLongOrNull() ?: return 0
        return ((id shr 22) % 6).toInt()
    }

    fun <T> lazy(cb: () -> T): () -> T {
        val lock = Any()
        var initialized = false
        var defaultValue: T? = null
        return {
            if (!initialized) synchronized(lock) {
                if (!initialized) { defaultValue = cb(); initialized = true }
            }
            @Suppress("UNCHECKED_CAST")
            defaultValue as T
        }
    }

    fun clearNullOrUndefinedObject(obj: Map<String, Any?>): Map<String, Any?>? {
        val data = mutableMapOf<String, Any?>()
        for ((key, value) in obj) {
            when {
                value == null -> continue
                value is List<*> && value.isEmpty() -> continue
                value is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val cleaned = clearNullOrUndefinedObject(value as Map<String, Any?>)
                    if (cleaned != null) data[key] = cleaned
                }
                else -> data[key] = value
            }
        }
        return if (data.isNotEmpty()) data else null
    }

    fun parseImage(image: Any?): String? {
        if (image !is String) return null
        if (Regex("^[0-9]{17,19}$").matches(image)) return image
        if (listOf("mp:", "youtube:", "spotify:", "twitch:").any { image.startsWith(it) }) return image
        if (image.startsWith("external/")) return "mp:$image"
        val isValidUrl = try {
            val uri = URI(image)
            uri.scheme == "http" || uri.scheme == "https"
        } catch (_: Exception) { false }
        if (!isValidUrl) return image
        var result = image
            .replace("https://cdn.discordapp.com/", "mp:")
            .replace("http://cdn.discordapp.com/", "mp:")
            .replace("https://media.discordapp.net/", "mp:")
            .replace("http://media.discordapp.net/", "mp:")
        if (!result.startsWith("mp:")) throw IllegalArgumentException("INVALID_URL")
        return result
    }

    suspend fun getExternal(
        rest: API, token: String, applicationId: String, vararg images: String
    ): List<Map<String, String>> {
        require(Regex("^[0-9]{17,19}$").matches(applicationId)) { "Application id must be a Discord Snowflake" }
        require(images.size <= 2) { "RichPresence can only have up to 2 external images" }
        require(images.all {
            try { val u = URI(it); u.scheme == "http" || u.scheme == "https" }
            catch (_: Exception) { false }
        }) { "Each image must be a valid URL" }
        val res = rest.api["applications"][applicationId]["external-assets"].post {
            headers = mapOf("Authorization" to token, "Content-Type" to "application/json")
            body = JsonObjectMapper.mapToJson(mapOf("urls" to images.toList()))
        }
        val json = Json.parseToJsonElement(res.bodyAsText()).jsonArray
        return json.map { element ->
            val obj = element.jsonObject
            mapOf("url" to (obj["url"]?.jsonPrimitive?.contentOrNull ?: ""),
                  "external_asset_path" to (obj["external_asset_path"]?.jsonPrimitive?.contentOrNull ?: ""))
        }
    }

    suspend fun refreshToken(rest: API, token: String, clientId: String, oldData: TokenResponse): TokenResponse {
        val res = rest.api["oauth2"]["token"].post {
            headers = mapOf("Authorization" to token, "Content-Type" to "application/x-www-form-urlencoded")
            body = "client_id=${URLEncoder.encode(clientId, "UTF-8")}&refresh_token=${URLEncoder.encode(oldData.refreshToken, "UTF-8")}&grant_type=refresh_token"
        }
        if (!res.status.value.toString().startsWith("2")) {
            val json = Json.parseToJsonElement(res.bodyAsText()).jsonObject
            if (json["error"]?.jsonPrimitive?.contentOrNull == "invalid_client")
                throw Exception("You must have the PUBLIC_OAUTH2_CLIENT application flag set.")
            else
                throw Exception("Failed to refresh token: ${res.status} - ${res.bodyAsText()}")
        }
        return Json.decodeFromString(res.bodyAsText())
    }
}
