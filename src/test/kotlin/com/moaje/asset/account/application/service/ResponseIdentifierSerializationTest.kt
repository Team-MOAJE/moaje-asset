package com.moaje.asset.account.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.moaje.asset.account.application.model.AccountTransactionItem
import com.moaje.asset.account.application.model.AccountTransactionScrollResult
import com.moaje.asset.transfer.domain.TransactionType
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.domain.TransactionCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class ResponseIdentifierSerializationTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    @DisplayName("거래 ID와 다음 커서는 문자열이며 마지막 커서는 null을 유지한다")
    fun serializesIdentifiersWithoutLosingPrecision() {
        // given
        val id = 123456789012345679L
        val item = AccountTransactionItem(id, "public-id", TransactionType.TRANSFER,
            BigDecimal.TEN, null, TransactionStatus.SUCCESS, null, TransactionCategory.TRANSFER, LocalDateTime.now())
        val page = AccountTransactionScrollResult(listOf(item), id, true)
        // when
        val json = mapper.readTree(mapper.writeValueAsString(page))
        val last = mapper.readTree(mapper.writeValueAsString(page.copy(nextCursor = null, hasNext = false)))
        // then
        assertThat(json["nextCursor"].isTextual).isTrue()
        assertThat(json["nextCursor"].asText()).isEqualTo(id.toString())
        assertThat(json["transactions"][0]["transactionId"].isTextual).isTrue()
        assertThat(json["transactions"][0]["amount"].isNumber).isTrue()
        assertThat(last["nextCursor"].isNull).isTrue()
    }
}
