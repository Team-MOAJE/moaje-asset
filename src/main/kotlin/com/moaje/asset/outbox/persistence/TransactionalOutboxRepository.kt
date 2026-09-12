package com.moaje.asset.outbox.persistence

import com.moaje.asset.outbox.domain.TransactionalOutbox
import org.springframework.data.jpa.repository.JpaRepository

interface TransactionalOutboxRepository : JpaRepository<TransactionalOutbox, Long> {
    fun findTop100ByAggregateTypeAndPublishedFalseAndDeliveryExcludedFalseOrderByCreatedAtAsc(aggregateType: String): List<TransactionalOutbox>
    fun findTop100ByPublishedFalseOrderByCreatedAtAsc(): List<TransactionalOutbox>

    fun findTop100ByAggregateTypeAndPublishedFalseOrderByCreatedAtAsc(aggregateType: String): List<TransactionalOutbox>
}


