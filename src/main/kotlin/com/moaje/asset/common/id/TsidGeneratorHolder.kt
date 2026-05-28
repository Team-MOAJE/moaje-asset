package com.moaje.asset.common.id

import com.github.f4b6a3.tsid.TsidFactory

object TsidGeneratorHolder {
    @Volatile
    private var factory: TsidFactory = createFactory(resolveNode())

    fun configure(node: Int) {
        factory = createFactory(node)
    }

    fun nextLong(): Long {
        return factory.create().toLong()
    }

    fun nextString(): String {
        return factory.create().toString()
    }

    private fun resolveNode(): Int {
        return System.getenv("MOAJE_TSID_NODE")?.toIntOrNull()
            ?: System.getProperty("moaje.tsid.node")?.toIntOrNull()
            ?: 0
    }

    private fun createFactory(node: Int): TsidFactory {
        require(node in 0..1023) { "TSID node??0 ?댁긽 1023 ?댄븯?ъ빞 ?⑸땲??" }

        return TsidFactory.builder()
            .withNodeBits(10)
            .withNode(node)
            .build()
    }
}

