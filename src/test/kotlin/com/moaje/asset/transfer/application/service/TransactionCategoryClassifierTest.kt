package com.moaje.asset.transfer.application.service

import com.moaje.asset.transfer.domain.TransactionCategory
import com.moaje.asset.transfer.domain.TransactionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TransactionCategoryClassifierTest {
    private val classifier = TransactionCategoryClassifier()

    @Nested
    @DisplayName("거래 유형 우선 분류")
    inner class TransactionTypeClassification {
        @Test
        @DisplayName("입금은 가맹점명과 관계없이 수입으로 분류한다")
        fun depositIsIncome() {
            assertThat(classifier.classify(TransactionType.DEPOSIT, "스타벅스"))
                .isEqualTo(TransactionCategory.INCOME)
        }

        @Test
        @DisplayName("송금은 가맹점명이 없어도 송금으로 분류한다")
        fun transferIsTransfer() {
            assertThat(classifier.classify(TransactionType.TRANSFER, null))
                .isEqualTo(TransactionCategory.TRANSFER)
        }

        @Test
        @DisplayName("반전 거래는 소비 카테고리로 분류하지 않는다")
        fun reversalIsUncategorized() {
            assertThat(classifier.classify(TransactionType.REVERSAL, "스타벅스"))
                .isEqualTo(TransactionCategory.UNCATEGORIZED)
        }
    }

    @Nested
    @DisplayName("출금 가맹점 분류")
    inner class MerchantClassification {
        @Test
        @DisplayName("여러 규칙이 일치하면 가장 긴 키워드를 우선한다")
        fun longestKeywordWins() {
            assertThat(classifier.classify(TransactionType.WITHDRAW, "스터디카페 강남점"))
                .isEqualTo(TransactionCategory.STUDY)
            assertThat(classifier.classify(TransactionType.WITHDRAW, "쿠팡이츠"))
                .isEqualTo(TransactionCategory.FOOD)
            assertThat(classifier.classify(TransactionType.WITHDRAW, "배달커피"))
                .isEqualTo(TransactionCategory.CAFE)
        }

        @Test
        @DisplayName("대소문자와 공백 차이를 제거한 뒤 분류한다")
        fun normalizesCaseAndWhitespace() {
            assertThat(classifier.classify(TransactionType.WITHDRAW, " 카카오 t "))
                .isEqualTo(TransactionCategory.TRANSPORT)
        }

        @Test
        @DisplayName("짧은 영문 키워드는 완전 일치할 때만 분류한다")
        fun shortEnglishKeywordsRequireExactMatch() {
            assertThat(classifier.classify(TransactionType.WITHDRAW, "CU"))
                .isEqualTo(TransactionCategory.SHOPPING)
            assertThat(classifier.classify(TransactionType.WITHDRAW, "KT"))
                .isEqualTo(TransactionCategory.HOUSING)
            assertThat(classifier.classify(TransactionType.WITHDRAW, "CULTURE SHOP"))
                .isEqualTo(TransactionCategory.UNCATEGORIZED)
            assertThat(classifier.classify(TransactionType.WITHDRAW, "MARKT PLACE"))
                .isEqualTo(TransactionCategory.UNCATEGORIZED)
        }

        @Test
        @DisplayName("가맹점명이 없거나 규칙에 없으면 미분류로 남긴다")
        fun unknownMerchantIsUncategorized() {
            assertThat(classifier.classify(TransactionType.WITHDRAW, null))
                .isEqualTo(TransactionCategory.UNCATEGORIZED)
            assertThat(classifier.classify(TransactionType.WITHDRAW, "   "))
                .isEqualTo(TransactionCategory.UNCATEGORIZED)
            assertThat(classifier.classify(TransactionType.WITHDRAW, "처음 보는 상점"))
                .isEqualTo(TransactionCategory.UNCATEGORIZED)
        }
    }
}
