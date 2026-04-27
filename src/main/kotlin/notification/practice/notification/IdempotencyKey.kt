package notification.practice.notification

import java.security.MessageDigest

object IdempotencyKey {
    fun derive(
        type: String,
        refType: String,
        refId: String,
        recipientId: Long,
        channel: NotificationChannel,
    ): String {
        val raw = "$type|$refType|$refId|$recipientId|$channel"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
