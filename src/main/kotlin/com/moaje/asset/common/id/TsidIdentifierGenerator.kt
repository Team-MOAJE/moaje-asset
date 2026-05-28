package com.moaje.asset.common.id

import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.generator.BeforeExecutionGenerator
import org.hibernate.generator.EventType
import org.hibernate.generator.EventTypeSets
import java.util.EnumSet

class TsidIdentifierGenerator : BeforeExecutionGenerator {
    override fun generate(
        session: SharedSessionContractImplementor,
        owner: Any,
        currentValue: Any?,
        eventType: EventType,
    ): Any {
        return TsidGeneratorHolder.nextLong()
    }

    override fun getEventTypes(): EnumSet<EventType> {
        return EventTypeSets.INSERT_ONLY
    }
}

