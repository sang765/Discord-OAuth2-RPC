# Discord OAuth2 RPC PoC (Kotlin)

This project is a Proof of Concept (PoC) that implements a small portion of the **Discord OAuth2** flow and **Rich Presence** updates via the Discord Gateway WebSocket — now rewritten in **Kotlin**.

## 🔄 Workflow

1. Proceed with the **Standard OAuth2** (PKCE) flow to authenticate the user.
2. Obtain a **Bearer Token** from the authorization process.
3. Use this token to connect directly to the **Discord WebSocket** to maintain the connection and send state payloads (Presence, Activities, etc.).

> [!NOTE]
> This demo is designed to be minimalistic to prove feasibility. Therefore, **it does not focus on security aspects** or the **long-term storage of Tokens**. In a real-world application, you would need to implement token encryption mechanisms, secure storage (like Keychain/Keystore), and handle Refresh Tokens properly.

## 🚀 Deployment Guide

### Prerequisites
- JDK 17+
- Gradle (or use the included wrapper)

### Step 1: Create an Application
Go to the [Discord Developer Portal](https://discord.com/developers/applications) and create a new Application.

### Step 2: Unlock the Social SDK Feature
Fill out the Social SDK registration form (**Games** > **Social SDK**) to unlock this feature for your Application.

*Note: The information in the form does not require absolute accuracy; you can fill it with dummy data to pass the review.*

<img src="https://blog.shizuku.tech/_astro/post-1.DWXDXVUN_sPsAA.webp">

### Step 3: Configure OAuth2
- Go to the newly created Application > Select the **OAuth2** tab.
- Enable the **"Public Client"** setting.
- Add your **Redirects URL** to the list (`http://127.0.0.1/callback`).

### Step 4: Start the Source Code
- Update `CLIENT_ID` in `src/main/kotlin/com/discord/oauth2rpc/Main.kt`.
- Run:

```sh
$ ./gradlew run
```

- Proceed with the OAuth2 flow just like authorizing a bot. After a short while, the RPC will appear on the user's account.

- The bearer token remains valid for up to 7 days. You can use the refresh flow to renew its expiration and continue using the token.

## Library Usage

The core library (`src/main/kotlin/com/discord/oauth2rpc/`) provides:

| Component | Description |
|---|---|
| `Gateway.kt` | Discord Gateway WebSocket client (Ktor + coroutines) |
| `Rest.kt` | REST API client with dynamic route builder |
| `Interface.kt` | `TokenResponse`, `GatewayPacket`, `ReadyEvent`, etc. |
| `structures/Presence.kt` | `RichPresence`, `CustomStatus`, `SpotifyRPC` builders |
| `utils/` | `BitField`, `Intents`, `GatewayCapabilities`, `ActivityFlags`, `Constants`, `Util` |

### Dependencies
- `ktor-client-okhttp` + `ktor-client-websockets` — HTTP & WebSocket
- `kotlinx-serialization-json` — JSON parsing
- `kotlinx-coroutines` — async concurrency

## Credits

- The images used in this README are sourced from @chirina's Discord Widget tutorial.
- Original TS implementation by @aiko-chan-ai.
