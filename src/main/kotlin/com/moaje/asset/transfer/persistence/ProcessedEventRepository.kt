package com.moaje.asset.transfer.persistence

import com.moaje.asset.transfer.domain.ProcessedEvent
import org.springframework.data.jpa.repository.JpaRepository

interface ProcessedEventRepository : JpaRepository<ProcessedEvent, String>
