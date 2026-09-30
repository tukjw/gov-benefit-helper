# 0004. 수집 실행 상태와 격리

- 상태: 채택
- 날짜: 2026-09-30
- 출처: #15, [플랜](../plans/15-policy-collection.md)

## 배경

`REQ-DATA-01`은 수집 실행마다 시작·종료 시각, 상태, 처리 건수를 확인할 수 있고 필수 식별자가 없는 응답은 오류 기록과 함께 격리하도록 요구한다. 장애 대응·재처리 이슈가 같은 기록 위에 부분 실패와 실패 구간을 더해야 한다.

## 결정

- 실행은 `collection_run`, 경로별 진행은 `collection_run_step`에 기록한다. 경로별로 쪽 수와 받은·신규·변경·동일·격리·미연결 건수를 남긴다.
- 상태는 문자열 컬럼이며 현재 값은 `RUNNING`, `SUCCEEDED`, `FAILED`다. 부분 실패는 경로별 상태로 표현하고, 실행 상태 값은 부분 실패를 다루는 이슈에서 추가한다.
- 서비스ID가 없거나 문자열이 아니거나 공백인 레코드는 `collection_quarantine`에 원문과 사유를 남기고 건너뛴다. 레코드 격리는 실행 실패가 아니다. 실패 상태는 요청 실패에만 쓴다.
- 실행 시작 행과 쪽 저장은 각각 커밋한다. 도중에 실패해도 그때까지의 기록과 저장 결과가 남는다.
- 실행 이력은 DB로 조회한다. 일반 사용자에게 수집 실행이나 이력 조회를 공개하지 않는다.

## 근거

- 합계 컬럼만 두면 "목록 성공, 상세 실패" 같은 부분 실패를 표현할 수 없다.
- 격리를 로그로만 남기면 원문을 다시 확인할 수 없다. 원문 테이블에 상태 행으로 넣으면 서비스ID 유니크 제약과 충돌한다.
- 레코드 몇 건의 결측으로 실행을 실패로 두면 정상 수집과 요청 실패를 구분할 수 없다.

## 적용 위치

- `backend/src/main/resources/db/migration/V1__create_benefit_collection_tables.sql`: `collection_run`, `collection_run_step`, `collection_quarantine`
- `backend/src/main/java/kr/govbenefit/collection/service/CollectionService.java`: 실행·경로 상태 전환
- `backend/src/main/java/kr/govbenefit/collection/service/CollectionPageWriter.java`: 격리

## 영향

- 재시도·부분 실패·실패 구간 재처리는 경로별 기록에 상태와 구간을 더하는 방식으로 확장한다.
- 수집 실패 경보는 `collection_run`·`collection_run_step`의 상태를 기준으로 한다.
