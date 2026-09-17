package com.moaje.asset.transfer.application.service

import com.moaje.asset.transfer.domain.TransactionCategory
import com.moaje.asset.transfer.domain.TransactionType
import org.springframework.stereotype.Component

/**
 * 거래 유형과 가맹점명을 기준으로 월간 집계용 카테고리를 결정한다.
 * 분류 근거가 부족하면 추측하지 않고 UNCATEGORIZED로 남긴다.
 */
@Component
class TransactionCategoryClassifier {

    fun classify(type: TransactionType, merchantName: String?): TransactionCategory {
        return when (type) {
            TransactionType.DEPOSIT -> TransactionCategory.INCOME
            TransactionType.TRANSFER -> TransactionCategory.TRANSFER
            TransactionType.REVERSAL -> TransactionCategory.UNCATEGORIZED
            TransactionType.WITHDRAW -> classifyMerchant(merchantName)
        }
    }

    private fun classifyMerchant(merchantName: String?): TransactionCategory {
        if (merchantName.isNullOrBlank()) return TransactionCategory.UNCATEGORIZED

        val normalized = normalize(merchantName)

        /*
         * CU나 KT처럼 짧은 영문은 다른 상호명의 일부에 포함될수있기에 완전일치되는 항목을 먼저 찾는다.
         */
        rules.firstOrNull { normalized in it.exactKeywords }
            ?.let { return it.category }

        /*
         * "스터디카페"처럼 여러 규칙에 걸리면 더 구체적인 긴 키워드를 선택
         * 길이가 같으면 rules에 먼저 선언된 카테고리를 우선한다.
         */
        var bestCategory = TransactionCategory.UNCATEGORIZED
        var maxLength = 0

        for (rule in rules) {
            for (keyword in rule.containsKeywords) {
                if (normalized.contains(keyword) && keyword.length > maxLength) {
                    maxLength = keyword.length
                    bestCategory = rule.category
                }
            }
        }
        return bestCategory
    }

    private data class CategoryRule(
        val category: TransactionCategory,
        val containsKeywords: Set<String>,
        val exactKeywords: Set<String> = emptySet(),
    )

    private companion object {
        /**
         * 상호명과 규칙을 같은 형태로 비교하기 위해 대소문자와 모든 공백 문자를 제거한다.
         * 규칙은 시작 시 한 번만 정규화해 거래를 분류할 때 불필요한 중간 객체를 만들지 않는다.
         */
        fun normalize(value: String): String = value
            .uppercase()
            .filterNot(Char::isWhitespace)

        fun rule(
            category: TransactionCategory,
            containsKeywords: Set<String>,
            exactKeywords: Set<String> = emptySet(),
        ) = CategoryRule(
            category = category,
            containsKeywords = containsKeywords.mapTo(linkedSetOf(), ::normalize),
            exactKeywords = exactKeywords.mapTo(linkedSetOf(), ::normalize),
        )

        val rules = listOf(
            rule(
                TransactionCategory.CAFE,
                setOf(
                    "스타벅스", "투썸", "메가커피", "이디야", "빽다방", "컴포즈", "할리스", "폴바셋", "파스쿠찌", "커피빈",
                    "카페", "커피", "베이커리", "제과", "파리바게뜨", "뚜레쥬르", "던킨", "배스킨라빈스",
                ),
            ),
            rule(
                TransactionCategory.FOOD,
                setOf(
                    "배달의민족", "우아한형제들", "요기요", "위대한상상", "쿠팡이츠",
                    "맥도날드", "버거킹", "롯데리아", "맘스터치", "KFC", "써브웨이",
                    "식당", "분식", "김밥", "국밥", "찌개", "반점", "가든", "회관", "횟집", "돈까스", "마라탕", "푸드", "치킨", "피자",
                ),
            ),
            rule(
                TransactionCategory.TRANSPORT,
                setOf(
                    "카카오T", "택시", "티머니", "코레일", "한국철도", "SRT", "에스알", "버스", "지하철",
                    "쏘카", "그린카", "대한항공", "아시아나", "제주항공",
                    "주유", "GS칼텍스", "SK엔크린", "S-OIL", "에쓰오일", "현대오일뱅크",
                ),
            ),
            rule(
                TransactionCategory.CULTURE,
                setOf(
                    "CGV", "메가박스", "롯데시네마", "인터파크티켓", "티켓링크",
                    "넷플릭스", "유튜브", "티빙", "웨이브", "왓챠", "멜론", "지니뮤직",
                    "야놀자", "여기어때", "에버랜드", "롯데월드", "전시", "미술관",
                ),
            ),
            rule(
                TransactionCategory.SHOPPING,
                setOf(
                    "쿠팡", "무신사", "11번가", "G마켓", "지마켓", "옥션", "티몬", "위메프", "SSG", "에이블리", "지그재그",
                    "이마트", "홈플러스", "롯데마트", "다이소", "올리브영",
                    "씨유", "GS25", "세븐일레븐", "이마트24", "미니스톱", "백화점",
                ),
                exactKeywords = setOf("CU","Action",),
            ),
            rule(
                TransactionCategory.STUDY,
                setOf(
                    "교보문고", "예스24", "YES24", "알라딘", "영풍문고", "밀리의서재", "리디북스",
                    "클래스101", "인프런", "패스트캠퍼스", "에듀윌", "해커스", "메가스터디",
                    "학원", "독서실", "스터디카페", "문구", "핫트랙스",
                ),
            ),
            rule(
                TransactionCategory.HOUSING,
                setOf(
                    "SKT", "LGU", "SK텔레콤", "LG유플러스", "알뜰폰", "통신",
                    "월세", "관리비", "도시가스", "한국전력", "한전", "수도요금",
                    "건강보험", "국민연금", "보험", "삼성화재", "현대해상", "DB손해보험",
                ),
                exactKeywords = setOf("KT"),
            ),
        )
    }
}
