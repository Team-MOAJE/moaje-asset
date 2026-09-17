-- 운영 MySQL과 같은 스키마를 검증하기 위한 테스트 DB 마이그레이션이다.
ALTER TABLE transaction_history
    ADD COLUMN merchant_name VARCHAR(100) NULL;

ALTER TABLE transaction_history
    ADD COLUMN category_code VARCHAR(50) NOT NULL DEFAULT 'UNCATEGORIZED';

-- 거래 유형만으로 분류가 확정되는 기존 수입과 송금만 보정한다.
UPDATE transaction_history
SET category_code = CASE
    WHEN type = 'DEPOSIT' THEN 'INCOME'
    WHEN type = 'TRANSFER' THEN 'TRANSFER'
    ELSE 'UNCATEGORIZED'
END;
