package com.moaje.asset.common.id

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moaje.id.tsid")
data class TsidIdProperties(
    val node: Int = 0,
) {
    init {
        require(node in 0..1023) { "TSID node는 0 이상 1023 이하여야 합니다." }
    }
}

