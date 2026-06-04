package com.discord.oauth2rpc

import com.discord.oauth2rpc.structures.CustomStatus
import com.discord.oauth2rpc.structures.RichPresence
import com.discord.oauth2rpc.structures.SpotifyRPC
import com.discord.oauth2rpc.utils.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.Executors

private const val CLIENT_ID = "<application_id>"
private val SCOPE = listOf("openid", "sdk.social_layer_presence").joinToString(" ")
private const val REDIRECT_URI = "http://127.0.0.1/callback"

private val mapState = Collections.synchronizedMap(mutableMapOf<String, String>())
private val mapUser = Collections.synchronizedMap(mutableMapOf<String, TokenResponse>())

private val gateway = GatewayClient()
private val rest = API()
private val mainScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

fun main() = runBlocking {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    var port = 0

    fun getRedirectURI(): String {
        val uri = URI(REDIRECT_URI)
        return URI(uri.scheme, uri.userInfo, uri.host, port, uri.path, uri.query, uri.fragment).toString()
    }

    server.createContext("/") { exchange ->
        val baseUrl = "https://discord.com/oauth2/authorize"
        val params = mutableListOf(
            "client_id=$CLIENT_ID",
            "redirect_uri=${URLEncoder.encode(getRedirectURI(), "UTF-8")}",
            "response_type=code",
            "scope=${URLEncoder.encode(SCOPE, "UTF-8")}"
        )

        val codeVerifier = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val codeChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray()))
        val state = UUID.randomUUID().toString()
        mapState[state] = codeVerifier

        params.add("code_challenge=$codeChallenge")
        params.add("code_challenge_method=S256")
        params.add("state=$state")

        val finalUrl = "$baseUrl?${params.joinToString("&")}"
        exchange.responseHeaders.add("Location", finalUrl)
        exchange.sendResponseHeaders(302, -1)
    }

    server.createContext("/callback") { exchange ->
        mainScope.launch {
            try {
                val query = exchange.requestURI.query ?: ""
                val params = query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                    else parts[0] to ""
                }
                val state = params["state"]
                val code = params["code"]
                val error = params["error"]
                val errorDescription = params["error_description"]

                if (state == null || !mapState.containsKey(state)) {
                    sendResponse(exchange, 400, "Invalid or missing state parameter"); return@launch
                }
                val codeVerifier = mapState.remove(state)!!

                if (error != null) { sendResponse(exchange, 400, "OAuth2 Error: $error - $errorDescription"); return@launch }
                if (code == null) { sendResponse(exchange, 400, "Missing code parameter"); return@launch }

                val resp = rest.api["oauth2"]["token"].post {
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
                    body = "client_id=$CLIENT_ID&grant_type=authorization_code&code=$code&code_verifier=$codeVerifier&redirect_uri=${URLEncoder.encode(getRedirectURI(), "UTF-8")}"
                }

                if (!resp.status.value.toString().startsWith("2")) {
                    sendResponse(exchange, 500, "Token exchange failed: ${resp.status} - ${resp.bodyAsText()}")
                    return@launch
                }

                val tokenData = Json.decodeFromString<TokenResponse>(resp.bodyAsText())

                val userResp = rest.api["users"]["@me"].get {
                    headers = mapOf("Authorization" to "${tokenData.tokenType} ${tokenData.accessToken}")
                }

                if (!userResp.status.value.toString().startsWith("2")) {
                    sendResponse(exchange, 500, "Failed to fetch user info: ${userResp.status} - ${userResp.bodyAsText()}")
                    return@launch
                }

                val bodyText = userResp.bodyAsText()
                val userDataJson = Json.parseToJsonElement(bodyText).jsonObject
                val userId = userDataJson["id"]!!.jsonPrimitive.content
                mapUser[userId] = tokenData

                try {
                    gateway.connect(GatewayConnectOptions(
                        token = "${tokenData.tokenType} ${tokenData.accessToken}",
                        identify = IdentifyPayload(capabilities = 0)
                    ))

                    val bytes = bodyText.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                    exchange.responseBody.close()
                } catch (err: Throwable) {
                    System.err.println("Gateway connection failed but REST OK: $err")
                    sendResponse(exchange, 500, """{"error":"Gateway connection failed","rest_response":$bodyText}""")
                }
            } catch (e: Exception) {
                sendResponse(exchange, 500, "Internal error: ${e.message}")
            }
        }
    }

    server.executor = Executors.newSingleThreadExecutor()
    server.start()
    port = server.address!!.port
    println("Server listening on http://localhost:$port")
    println("Open browser at: http://localhost:$port/")

    gateway.onReady = { ready ->
        println("Logged in as @${ready.user.username} (${ready.user.id})")

        val spotifyRPC = SpotifyRPC(ready.user.id, ready.sessionId).apply {
            setAssetsLargeImage("spotify:ab67616d00001e02768629f8bc5b39b68797d1bb")
            setAssetsSmallImage("spotify:ab6761610000f178049d8aeae802c96c8208f3b7")
            setAssetsLargeText("未来茶屋 (vol.1)")
            setState("Yunomi; Kizuna AI")
            setDetails("ロボットハート")
            setStartTimestamp(System.currentTimeMillis())
            setEndTimestamp(System.currentTimeMillis() + 1000L * (2 * 60 + 56))
            setSongId("667eE4CFfNtJloC6Lvmgrx")
            setAlbumId("6AAmvxoPoDbJAwbatKwMb9")
            setArtistIds("2j00CVYTPx6q9ANbmB2keb", "2nKGmC5Mc13ct02xAY8ccS")
        }

        val tokenData = mapUser[ready.user.id]
        var externalAssets: List<Map<String, String>> = emptyList()
        if (tokenData != null) {
            try {
                externalAssets = runBlocking {
                    Util.getExternal(rest, "${tokenData.tokenType} ${tokenData.accessToken}", CLIENT_ID,
                        "https://assets.ppy.sh/beatmaps/1550633/covers/list.jpg")
                }
            } catch (_: Exception) {}
        }

        val status = RichPresence(ready.sessionId)
            .setApplicationId("367827983903490050")
            .setType("PLAYING")
            .setState("Arcade Game")
            .setName("osu!")
            .setDetails("MariannE - Yooh")
            .setParty(mapOf("max" to 8, "current" to 1))
            .setStartTimestamp(System.currentTimeMillis())
            .setAssetsLargeImage(if (externalAssets.isNotEmpty()) externalAssets[0]["external_asset_path"] else null)
            .setAssetsLargeText("Idle")
            .setAssetsSmallImage("373370493127884800")
            .setAssetsSmallText("click the circles")
            .setPlatform("desktop")
            .addButton("Beatmap", "https://osu.ppy.sh/beatmapsets/1391659#osu/2873429")

        val custom = CustomStatus().setEmoji(EmojiIdentifierResolvable.StringVal("😋")).setState("yum")

        val presenceJson = buildJsonObject {
            putJsonArray("activities") {
                add(Json.parseToJsonElement(JsonObjectMapper.mapToJson(spotifyRPC.toJSON() as Map<String, Any>)))
                add(Json.parseToJsonElement(JsonObjectMapper.mapToJson(status.toJSON() as Map<String, Any>)))
                add(Json.parseToJsonElement(JsonObjectMapper.mapToJson(custom.toJSON() as Map<String, Any>)))
            }
            put("afk", false)
            put("since", "0")
            put("status", "idle")
        }

        gateway.send(GatewayOp.PRESENCE_UPDATE, presenceJson)
    }

    gateway.onDebug = { msg -> println(msg) }

    Thread.currentThread().join()
}

private fun sendResponse(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
    val bytes = body.toByteArray()
    exchange.sendResponseHeaders(code, bytes.size.toLong())
    exchange.responseBody.write(bytes)
    exchange.responseBody.close()
}
