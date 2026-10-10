# Moaje Asset

모아제의 자산 조회 서비스입니다. Banking 이벤트와 계정계 스냅샷으로 잔액·거래내역을 갱신하고 하루 생활비를 계산합니다. 실제 출금 승인이나 금융 원장 관리는 담당하지 않습니다.

- [개발 기록과 검증 결과](https://github.com/Team-MOAJE/moaje-infra/blob/main/docs/phase-history-and-retrospective.md)
- [공개 문서와 ADR](https://github.com/Team-MOAJE/moaje-infra/blob/main/docs/README.md)
- [Asset gRPC 계약](https://github.com/Team-MOAJE/moaje-grpc-contracts/blob/main/proto/grpc/asset_service.proto)
- [Asset 이벤트 계약](https://github.com/Team-MOAJE/moaje-grpc-contracts/blob/main/proto/events/asset_events.proto)
- [로컬 통합 실행](https://github.com/Team-MOAJE/moaje-infra/blob/main/readMe.md)

Asset 데이터는 비동기로 반영되는 조회용 데이터입니다. Work의 실제 소비 연동과 Swagger는 후속 작업이며, 검증 범위와 남은 한계는 개발 기록에 구분해 남깁니다.

## 패키지 구성

Banking과 동일하게 **기능을 먼저, 역할을 그 안에** 나눕니다. Asset의 주요 기능은 `account`, `transfer`, `cashflow`, `outbox`입니다.

| 기능 아래의 경로 | 담는 코드 |
|---|---|
| `web` | HTTP 요청을 받고 업무를 호출하는 Controller |
| `application/service` | 업무 인터페이스와 실제 처리 클래스 |
| `application/model` | 업무 요청·결과와 조회 응답 데이터 |
| `application/query` | 외부 서비스에 필요한 조회 기능을 선언한 인터페이스 |
| `domain` | 계좌·거래 상태와 변경 규칙 |
| `persistence` | DB 조회·저장 Repository |
| `messaging` | Kafka 이벤트 발행·소비 |
| `integration` | 외부 서비스와 실제로 통신하는 Client |

업무 인터페이스와 구현체가 나뉜 경우 이름은 `XxxService`와 `XxxServiceImpl`로 맞춥니다. 인터페이스가 없는 서비스에는 `Impl`을 붙이지 않습니다.

예를 들어 `account/application/service/AccountDetailService.kt`는 조회 업무를 선언하고, 같은 폴더의 `AccountDetailServiceImpl.kt`가 처리합니다. 입력과 결과는 `account/application/model/AccountDetailModels.kt`에 있습니다. 기존 `usecase` 폴더는 이 두 역할로 나눴습니다.

`account/application/query`의 Banking 조회 계약은 `account/integration/banking/BankingGrpcClient.kt`가 구현합니다. gRPC 요청 접수는 `api/grpc`, 공통 인증·설정은 `common`, 정기 대사는 기능 내부의 `application/reconciliation`에 둡니다. 필요한 역할이 있을 때만 폴더를 추가합니다.
