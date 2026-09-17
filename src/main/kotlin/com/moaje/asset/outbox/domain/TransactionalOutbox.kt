package com.moaje.asset.outbox.domain

import com.moaje.asset.common.id.TsidGeneratedValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Lob
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(
    name = "transactional_outbox",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_transactional_outbox_deduplication_key", columnNames = ["deduplication_key"]),
    ],
    indexes = [
        Index(name = "idx_transactional_outbox_published", columnList = "is_published"),
        Index(name = "idx_transactional_outbox_created_at", columnList = "created_at"),
    ],
)
class TransactionalOutbox(
    @Id
    @TsidGeneratedValue
    @Column(name = "id", nullable = false)
    var id: Long? = null,

    @Column(name = "aggregate_type", nullable = false, length = 50)
    var aggregateType: String,

    @Column(name = "aggregate_id", nullable = false)
    var aggregateId: Long,

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    var payload: String,

    @Column(name = "is_published", nullable = false)
    var published: Boolean = false,

    @Column(name = "delivery_excluded", nullable = false)
    var deliveryExcluded: Boolean = false,

    // 같은 사용자·월·revision의 집계 이벤트가 중복 생성되는 것을 DB가 마지막으로 차단한다.
    @Column(name = "deduplication_key", length = 180)
    var deduplicationKey: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
) {
    fun markPublished() {
        published = true
    }

    fun excludeFromDelivery() {
        deliveryExcluded = true
    }
}


