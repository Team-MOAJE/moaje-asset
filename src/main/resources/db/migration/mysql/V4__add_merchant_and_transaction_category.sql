-- 가맹점명은 개인 간 송금이나 ATM 거래에는 존재하지 않을 수 있으므로 NULL을 허용한다.
ALTER TABLE transaction_history
    ADD COLUMN merchant_name VARCHAR(100) NULL;

-- 새 거래는 Asset 분류기가 값을 정하며, 기존 또는 미분류 거래는 안전하게 UNCATEGORIZED로 시작한다.
ALTER TABLE transaction_history
    ADD COLUMN category_code VARCHAR(50) NOT NULL DEFAULT 'UNCATEGORIZED';

-- 과거 데이터 중 거래 유형만으로 의미가 확정되는 수입과 송금만 보정한다.
-- 출금은 가맹점 정보 없이 임의 분류하지 않고 이후 분류 단계까지 UNCATEGORIZED로 유지한다.
UPDATE transaction_history
SET category_code = CASE
    WHEN type = 'DEPOSIT' THEN 'INCOME'
    WHEN type = 'TRANSFER' THEN 'TRANSFER'
    ELSE 'UNCATEGORIZED'
END;
