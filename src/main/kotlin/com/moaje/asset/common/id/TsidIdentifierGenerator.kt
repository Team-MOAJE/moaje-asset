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
        // Banking AccountCreated 이벤트가 전달한 accountId가 있으면 그대로 사용해 서비스 간 계좌 식별자를 일치시킨다.
        // 신규 Asset 자체 생성만 TSID를 발급하며, 외부 식별자를 조용히 다른 값으로 바꾸지 않는다.
        return currentValue ?: TsidGeneratorHolder.nextLong()
    }

    override fun getEventTypes(): EnumSet<EventType> {
        return EventTypeSets.INSERT_ONLY
    }

    override fun allowAssignedIdentifiers(): Boolean = true
}


