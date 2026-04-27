package notification.practice.notification.template

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import notification.practice.notification.NotificationChannel
import java.time.Instant

@Entity
@Table(
    name = "notification_template",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_template_type_channel_version",
            columnNames = ["type", "channel", "version"],
        ),
    ],
    indexes = [
        Index(
            name = "ix_template_type_channel_active",
            columnList = "type, channel, active",
        ),
    ],
)
class NotificationTemplate(
    @Column(nullable = false, length = 50)
    val type: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val channel: NotificationChannel,
    @Column(nullable = false, length = 200)
    val subject: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    val body: String,
    @Column(nullable = false)
    val version: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    fun deactivate() {
        active = false
    }
}
