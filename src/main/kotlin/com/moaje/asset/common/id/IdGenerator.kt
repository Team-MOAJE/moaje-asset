package com.moaje.asset.common.id

import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class IdGenerator {
    fun nextId(): Long {
        return TsidGeneratorHolder.nextLong()
    }

    fun nextBankingTransferPublicId(date: LocalDate = LocalDate.now()): String {
        val dateText = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val tsidText = TsidGeneratorHolder.nextString()

        return "MOAJE-BNK-$dateText-$tsidText"
    }
}

