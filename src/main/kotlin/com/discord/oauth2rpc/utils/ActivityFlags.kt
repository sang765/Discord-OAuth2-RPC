package com.discord.oauth2rpc.utils

class ActivityFlags(bits: Int = 0) : BitField(bits) {
    override val flags: Map<String, Int> get() = Companion.FLAGS

    companion object {
        val FLAGS: Map<String, Int> = mapOf(
            "INSTANCE" to (1 shl 0),
            "JOIN" to (1 shl 1),
            "SPECTATE" to (1 shl 2),
            "JOIN_REQUEST" to (1 shl 3),
            "SYNC" to (1 shl 4),
            "PLAY" to (1 shl 5),
            "PARTY_PRIVACY_FRIENDS" to (1 shl 6),
            "PARTY_PRIVACY_VOICE_CHANNEL" to (1 shl 7),
            "EMBEDDED" to (1 shl 8),
        )

        val ALL: Int = FLAGS.values.reduce { a, b -> a or b }
    }
}
