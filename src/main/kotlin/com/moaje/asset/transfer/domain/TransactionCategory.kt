package com.moaje.asset.transfer.domain

/**
 * 월별 소비 집계에 사용하는 큰 범주의 거래 카테고리다.
 * OTHER는 분류가 끝난 기타 거래이고, UNCATEGORIZED는 아직 분류 근거가 부족한 거래다.
 */
enum class TransactionCategory {
    INCOME,
    FOOD,
    CAFE,
    TRANSPORT,
    CULTURE,
    SHOPPING,
    STUDY,
    HOUSING,
    TRANSFER,
    OTHER,
    UNCATEGORIZED,
}
