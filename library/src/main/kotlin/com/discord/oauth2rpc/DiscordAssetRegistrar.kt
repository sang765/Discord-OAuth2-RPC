package com.discord.oauth2rpc

import org.json.JSONArray
import java.net.URI

class DiscordAssetRegistrar(
    private val rest: API,
    private val applicationId: String,
    private val token: String
) {
    private val cache = mutableMapOf<String, String>()

    suspend fun resolve(image: String): String {
        cache[image]?.let { return it }
        val resolved = resolveInternal(image)
        cache[image] = resolved
        return resolved
    }

    suspend fun resolveAll(vararg images: String): List<String> {
        val results = arrayOfNulls<String>(images.size)
        val externalUrls = mutableListOf<Pair<Int, String>>()
        for ((i, image) in images.withIndex()) {
            val cached = cache[image]
            if (cached != null) {
                results[i] = cached
            } else if (needsRegistration(image)) {
                externalUrls.add(i to image)
            } else {
                val resolved = resolveImmediate(image)
                cache[image] = resolved
                results[i] = resolved
            }
        }
        if (externalUrls.isNotEmpty()) {
            val urls = externalUrls.map { it.second }.toTypedArray()
            val registered = register(*urls)
            for ((idx, reg) in registered.withIndex()) {
                val originalIdx = externalUrls[idx].first
                results[originalIdx] = reg.resolved
            }
        }
        return results.filterNotNull()
    }

    suspend fun register(vararg urls: String): List<RegisterResult> {
        require(urls.isNotEmpty()) { "At least one URL must be provided" }
        require(urls.all { isValidHttpUrl(it) }) { "Each value must be a valid HTTP(S) URL" }
        val uncached = urls.filter { it !in cache }
        if (uncached.isNotEmpty()) {
            val res = rest.api["applications"][applicationId]["external-assets"].post {
                headers = mapOf("Authorization" to token, "Content-Type" to "application/json")
                body = JsonObjectMapper.mapToJson(mapOf("urls" to uncached.toList()))
            }
            val json = JSONArray(res.bodyAsText())
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val url = obj.optString("url", "")
                val assetPath = obj.optString("external_asset_path", "")
                cache[url] = "mp:$assetPath"
            }
        }
        return urls.map { url ->
            val resolved = cache[url] ?: url
            RegisterResult(
                original = url,
                resolved = resolved,
                externalAssetPath = resolved.removePrefix("mp:").ifEmpty { null }
            )
        }
    }

    fun clearCache() {
        cache.clear()
    }

    private fun needsRegistration(image: String): Boolean {
        if (snowflake.matches(image)) return false
        if (prefixes.any { image.startsWith(it) }) return false
        if (image.startsWith("external/")) return false
        if (isDiscordCdnUrl(image)) return false
        return isValidHttpUrl(image)
    }

    private fun resolveImmediate(image: String): String {
        if (snowflake.matches(image)) return image
        if (prefixes.any { image.startsWith(it) }) return image
        if (image.startsWith("external/")) return "mp:$image"
        if (isDiscordCdnUrl(image)) return convertDiscordCdn(image)
        return image
    }

    private suspend fun resolveInternal(image: String): String {
        val immediate = resolveImmediate(image)
        if (immediate != image) {
            cache[image] = immediate
            return immediate
        }
        if (isValidHttpUrl(image)) {
            val result = register(image)
            if (result.isNotEmpty()) return result.first().resolved
        }
        return image
    }

    private fun isDiscordCdnUrl(url: String): Boolean {
        return DISCORD_CDN_PREFIXES.any { url.startsWith(it) }
    }

    private fun convertDiscordCdn(url: String): String {
        var result = url
        for (prefix in DISCORD_CDN_PREFIXES) {
            result = result.replace(prefix, "mp:")
        }
        return result
    }

    private fun isValidHttpUrl(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "http" || uri.scheme == "https"
    } catch (_: Exception) {
        false
    }

    companion object {
        private val snowflake = Regex("^[0-9]{17,19}$")
        private val prefixes = listOf("mp:", "youtube:", "spotify:", "twitch:")
        private val DISCORD_CDN_PREFIXES = listOf(
            "https://cdn.discordapp.com/",
            "http://cdn.discordapp.com/",
            "https://media.discordapp.net/",
            "http://media.discordapp.net/"
        )
    }
}

data class RegisterResult(
    val original: String,
    val resolved: String,
    val externalAssetPath: String?
)
