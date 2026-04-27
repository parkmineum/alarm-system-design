package notification.practice.notification.domain

import java.security.MessageDigest
import java.time.Instant

object IdempotencyKey {
    fun derive(
        type: String,
        refType: String,
        refId: String,
        recipientId: Long,
        channel: NotificationChannel,
        scheduledAt: Instant? = null,
    ): String {
        val raw =
            buildString {
                append("$type|$refType|$refId|$recipientId|$channel")
                if (scheduledAt != null) append("|${scheduledAt.toEpochMilli()}")
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
