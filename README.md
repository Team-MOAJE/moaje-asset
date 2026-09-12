# Moaje Asset

모아제의 자산 조회 서비스입니다. Banking 이벤트와 계정계 스냅샷으로 잔액·거래내역을 갱신하고 하루 생활비를 계산합니다. 실제 출금 승인이나 금융 원장 관리는 담당하지 않습니다.

- [개발 기록과 검증 결과](https://github.com/Team-MOAJE/moaje-infra/blob/main/docs/phase-history-and-retrospective.md)
- [공개 문서와 ADR](https://github.com/Team-MOAJE/moaje-infra/blob/main/docs/README.md)
- [Asset gRPC 계약](https://github.com/Team-MOAJE/moaje-grpc-contracts/blob/main/proto/grpc/asset_service.proto)
- [Asset 이벤트 계약](https://github.com/Team-MOAJE/moaje-grpc-contracts/blob/main/proto/events/asset_events.proto)
- [로컬 통합 실행](https://github.com/Team-MOAJE/moaje-infra/blob/main/readMe.md)

Asset 데이터는 비동기로 반영되는 조회용 데이터입니다. Work의 실제 소비 연동과 Swagger는 후속 작업이며, 검증 범위와 남은 한계는 개발 기록에 구분해 남깁니다.
