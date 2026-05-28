package com.moaje.asset.common.id

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moaje.id.tsid")
data class TsidIdProperties(
    val node: Int = 0,
) {
    init {
        require(node in 0..1023) { "TSID node??0 ?댁긽 1023 ?댄븯?ъ빞 ?⑸땲??" }
    }
}

