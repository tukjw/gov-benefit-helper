# 0007. 수집 실행 상태와 실패 기록

- 상태: 채택
- 날짜: 2026-10-01
- 출처: #16, [플랜](../plans/16-collection-fault-tolerance.md)
- 대체: [0004](0004-collection-run-status.md)

## 배경

0004는 실행 상태를 `RUNNING`, `SUCCEEDED`, `FAILED`로 두고 "실패 상태는 요청 실패에만 쓴다"고 정했다. 부분 실패를 다루면서 실행 상태에 `PARTIAL`을 더하고, 쪽 저장 실패(DB 오류)도 실패로 기록하게 되어 0004를 대체한다. 0004의 나머지 결정은 그대로 옮겼다.

## 결정

- 실행은 `collection_run`, 경로별 진행은 `collection_run_step`에 기록한다. 경로별로 쪽 수와 받은·신규·변경·동일·격리·미연결 건수를 남긴다.
- 상태는 문자열 컬럼이며 값은 `RUNNING`, `SUCCEEDED`, `PARTIAL`, `FAILED`다.
  - 실행: 목록 1쪽을 받아 저장하지 못하면 `FAILED`이고 이후 경로를 받지 않는다. 그 밖에 실패한 쪽이 있거나 도중에 중단되면 `PARTIAL`, 없으면 `SUCCEEDED`다. 예상하지 못한 오류는 `FAILED`다.
  - 경로: 1쪽을 저장하지 못하면 `FAILED`, 이후 실패한 쪽이 있거나 도중에 중단되면 `PARTIAL`, 없으면 `SUCCEEDED`다. 중단 뒤의 경로는 경로 행을 만들지 않는다.
- 실패한 쪽은 저장하지 않고 건너뛴 뒤 다음 쪽을 받는다. 경로의 1쪽이 실패하면 마지막 쪽을 알 수 없으므로 그 경로는 끝낸다.
- 쪽 단위 실패는 모두 `collection_failure`(실행, 경로, 쪽, 유형, 시도 횟수, 메시지)에 남긴다. `collection_run.error_type`·`error_message`에는 실행을 `FAILED`로 만들거나 중단시킨 원인만 두고, 끝까지 돈 실행은 비워 둔다. 유형은 [0006](0006-gov24-failure-handling.md)을 따른다.
- 실패 메시지에는 경로·쪽·상태 코드·응답의 `code`·`msg`, DB 오류는 원인 예외 클래스 이름만 담는다. 인증키·요청 헤더·레코드 값은 담지 않는다.
- 서비스ID가 없거나 문자열이 아니거나 공백인 레코드는 `collection_quarantine`에 원문과 사유를 남기고 건너뛴다. 레코드 격리는 실행 실패가 아니다.
- 실행 시작 행, 쪽 저장, 실패 기록은 각각 커밋한다. 도중에 실패해도 그때까지의 기록과 저장 결과가 남는다.
- 실행 이력은 DB로 조회한다. 일반 사용자에게 수집 실행이나 이력 조회를 공개하지 않는다.

## 근거

- 실행 상태는 "이번 실행으로 갱신이 됐는가"로 나눈다. 목록 1쪽이 실패하면 전체 건수를 몰라 갱신이 전혀 없다. 목록 중간 쪽이 빠져도 기존 혜택은 지워지지 않고 마지막 확인 시각만 이전 값으로 남으며, 그 쪽 신규 혜택의 상세는 미연결 원문으로 남았다가 다음 실행에서 연결된다.
- 실패한 쪽이 여럿이고 원인이 다를 수 있어 실행 행의 컬럼 하나로는 표현할 수 없다. 실패 구간 재처리(`REQ-DATA-01` P1)가 `collection_failure`를 입력으로 쓴다. 경로 행에 원인 컬럼을 두면 실패 테이블과 중복된다.
- 실패한 쪽 전체를 쓰지 않으므로 실패한 요청이 기존 정상 값을 바꾸지 않는다. 정상 응답의 빈 값까지 막는 필드 단위 보호는 원천이 실제로 값을 지운 경우를 숨기므로 두지 않았다.
- 0004의 근거(합계 컬럼만으로는 부분 실패를 표현할 수 없음, 격리 원문 보관, 격리와 요청 실패의 구분)는 그대로 유효하다.

## 적용 위치

- `backend/src/main/resources/db/migration/V1__create_benefit_collection_tables.sql`: `collection_run`, `collection_run_step`, `collection_quarantine`
- `backend/src/main/resources/db/migration/V2__add_collection_failure.sql`: `collection_run.error_type`, `collection_failure`
- `backend/src/main/java/kr/govbenefit/collection/service/CollectionService.java`: 실행·경로 상태 전환, 실패 기록
- `backend/src/main/java/kr/govbenefit/collection/service/CollectionPageWriter.java`: 격리

## 영향

- 실패 구간 재처리는 `collection_failure`의 경로·쪽을 입력으로 한다.
- 수집 실패 경보는 `collection_run`의 상태와 `error_type`을 기준으로 한다.
- 실행이 겹치거나 도중에 프로세스가 죽어 `RUNNING`으로 남은 실행의 정리는 실행 겹침 처리(`REQ-QUALITY-01` P1)에서 정한다.
