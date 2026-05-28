package com.moaje.asset.repository.outbox

import com.moaje.asset.domain.outbox.TransactionalOutbox
import org.springframework.data.jpa.repository.JpaRepository

interface TransactionalOutboxRepository : JpaRepository<TransactionalOutbox, Long> {
    fun findTop100ByPublishedFalseOrderByCreatedAtAsc(): List<TransactionalOutbox>

    fun findTop100ByAggregateTypeAndPublishedFalseOrderByCreatedAtAsc(aggregateType: String): List<TransactionalOutbox>
}

