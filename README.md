# Moaje Asset Domain

Moaje Asset 서비스는 사용자의 계좌 원장, 거래 내역, 이벤트 아웃박스, Daily 가용 생활비 스냅샷을 관리하는 도메인입니다.

Banking 서비스가 실제 금융망 처리를 담당한다면, Asset 서비스는 서비스 내부에서 신뢰할 수 있는 잔액과 거래 상태를 확정하고 보관하는 역할을 맡습니다.

## 현재 개발 범위

- 계좌 원장 `account` JPA 엔티티 및 Repository 구성
- 거래 내역 `transaction_history` JPA 엔티티 및 Repository 구성
- 이벤트 아웃박스 `transactional_outbox` JPA 엔티티 및 Repository 구성
- Daily 가용 생활비 스냅샷 `daily_cashflow_snapshot` JPA 엔티티 및 Repository 구성
- TSID 기반 내부 PK 및 외부 노출 ID 생성 컴포넌트 구성
- Banking 서비스와의 gRPC 서버/클라이언트 골격 및 실제 호출 흐름 구성
- Kafka 이벤트 소비/발행 흐름 구성
- Redis 캐시 설정 구성
- 목업뱅킹 거래내역 동기화를 위한 Banking 연동 인터페이스 구성
- Dockerfile 추가

## 주요 책임

- 송금 요청 시 계좌 잔액을 선차감하고 거래를 `PENDING` 상태로 기록합니다.
- 선차감 완료 후 Banking 서비스가 실제 금융망 처리를 진행할 수 있도록 송금 요청 이벤트를 발행합니다.
- Banking 서비스의 송금 성공/실패 이벤트를 받아 거래 상태를 `SUCCESS` 또는 `FAILED`로 확정합니다.
- 외부 금융망에서 추가로 발견된 입출금 거래는 `externalTransactionId` 기준으로 멱등 처리합니다.
- 메인 화면에 필요한 Daily 가용 생활비를 계산하고 스냅샷으로 관리합니다.

## HTTP API

기본 포트는 `8082`입니다.

### Daily 가용 생활비 계산

`POST /api/v1/assets/cashflow/daily-limit`

```json
{
  "userId": 1,
  "expectedIncome": 500000,
  "fixedExpenses": 200000,
  "eventBuffer": 50000,
  "daysUntilNextPayday": 10,
  "snapshotDate": "2026-05-29",
  "forceRefresh": false
}
```

### 계좌 거래내역 새로고침

`POST /api/v1/assets/accounts/{accountToken}/transactions/refresh`

```json
{
  "userId": 1,
  "cursor": "1970-01-01T00:00:00Z"
}
```

현재 Auth 서비스의 계좌정보 조회 RPC 스펙은 확정 전이므로, 관련 클라이언트는 추후 연결할 수 있도록 인터페이스 중심으로 구성되어 있습니다.

## gRPC

Asset 서비스는 기본적으로 `9090` 포트에서 gRPC 서버를 엽니다.

주요 메서드는 `moaje-grpc-contracts/proto/grpc/asset_service.proto`를 기준으로 합니다.

- `ReserveTransfer`: 송금 전 가용 잔액 선차감 및 PENDING 거래 생성
- `CompleteTransfer`: Banking 송금 성공 결과 반영
- `FailTransfer`: Banking 송금 실패 결과 반영 및 선차감 복구
- `ApplyExternalTransaction`: 외부 금융망 동기화 거래 반영
- `GetDailyCashflow`: Daily 가용 생활비 조회

## Kafka

현재 사용하는 주요 토픽은 다음과 같습니다.

- `moaje.asset.transfer-requested`
- `moaje.banking.transfer-completed`
- `moaje.banking.transfer-failed`
- `transaction_succeeded_events`

이벤트 메시지 규약은 `moaje-grpc-contracts/proto/events` 하위 proto 파일을 기준으로 합니다.

## 실행 설정

주요 기본 설정은 `src/main/resources/application.yml`에 있습니다.

- HTTP: `8082`
- gRPC server: `9090`
- Banking gRPC target: `localhost:9091`
- Kafka: `localhost:9092`
- Redis: `localhost:6379`

로컬 실행:

```bash
./gradlew bootRun
```

테스트:

```bash
./gradlew test
```

Docker 이미지는 프로젝트 루트의 `Dockerfile`을 기준으로 빌드합니다.
