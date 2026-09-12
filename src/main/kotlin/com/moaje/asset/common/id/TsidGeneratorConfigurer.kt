package com.moaje.asset.common.id

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class TsidGeneratorConfigurer(
    private val properties: TsidIdProperties,
) {
    @PostConstruct
    fun configure() {
        TsidGeneratorHolder.configure(properties.node)
    }
}


