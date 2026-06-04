package com.discord.oauth2rpc

import com.discord.oauth2rpc.utils.DEFAULTS
import com.discord.oauth2rpc.utils.DEFAULT_SUPER_PROPERTIES
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.io.Closeable
import java.util.*

class API(val baseURL: String = DEFAULTS.API_BASE) : Closeable {
    private val client = HttpClient()

    val api: RouteBuilder get() = RouteBuilder(baseURL, client)

    override fun close() {
        client.close()
    }
}

class RouteBuilder(
    private val baseUrl: String,
    private val client: HttpClient,
    private val path: String = ""
) {

    operator fun get(key: String): RouteBuilder {
        val newPath = if (path.isEmpty()) key else "$path/$key"
        return RouteBuilder(baseUrl, client, newPath)
    }

    operator fun invoke(vararg args: Any?): RouteBuilder {
        val filtered = args.filterNotNull().map { it.toString() }
        if (filtered.isEmpty()) return this
        val suffix = filtered.joinToString("/")
        val newPath = if (path.isEmpty()) suffix else "$path/$suffix"
        return RouteBuilder(baseUrl, client, newPath)
    }

    suspend fun get(block: suspend RequestConfig.() -> Unit = {}): HttpResponse {
        return execute(HttpMethod.Get, block)
    }

    suspend fun post(block: suspend RequestConfig.() -> Unit = {}): HttpResponse {
        return execute(HttpMethod.Post, block)
    }

    suspend fun delete(block: suspend RequestConfig.() -> Unit = {}): HttpResponse {
        return execute(HttpMethod.Delete, block)
    }

    suspend fun patch(block: suspend RequestConfig.() -> Unit = {}): HttpResponse {
        return execute(HttpMethod.Patch, block)
    }

    suspend fun put(block: suspend RequestConfig.() -> Unit = {}): HttpResponse {
        return execute(HttpMethod.Put, block)
    }

    private suspend fun execute(method: HttpMethod, block: suspend RequestConfig.() -> Unit): HttpResponse {
        val url = baseUrl.trimEnd('/') + "/" + path

        val config = RequestConfig().apply { block() }

        return client.request(url) {
            this.method = method

            val superPropsBase64 = Base64.getEncoder().encodeToString(
                JsonObjectMapper.mapToJson(DEFAULT_SUPER_PROPERTIES).toByteArray()
            )

            config.headers?.forEach { (k, v) -> headers { append(k, v) } }

            headers {
                append("User-Agent", DEFAULTS.USER_AGENT)
                append("X-Super-Properties", superPropsBase64)
            }

            config.body?.let { body ->
                when (body) {
                    is String -> setBody(body)
                    is ByteArray -> setBody(body)
                    else -> setBody(body.toString())
                }
            }
        }
    }

    override fun toString(): String = path
}

class RequestConfig {
    var headers: Map<String, String>? = null
    var body: Any? = null
}

object JsonObjectMapper {
    fun mapToJson(map: Map<String, Any?>): String {
        val sb = StringBuilder()
        serialize(sb, map)
        return sb.toString()
    }

    private fun serialize(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> {
                sb.append('"')
                value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").also { sb.append(it) }
                sb.append('"')
            }
            is Number -> sb.append(value)
            is Boolean -> sb.append(value)
            is Map<*, *> -> {
                sb.append('{')
                val entries = value.entries.toList()
                for ((i, entry) in entries.withIndex()) {
                    if (i > 0) sb.append(',')
                    serialize(sb, entry.key.toString())
                    sb.append(':')
                    serialize(sb, entry.value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                val list = value.toList()
                for ((i, item) in list.withIndex()) {
                    if (i > 0) sb.append(',')
                    serialize(sb, item)
                }
                sb.append(']')
            }
            else -> sb.append(value)
        }
    }
}
