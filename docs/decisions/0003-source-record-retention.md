# 0003. 원문 보관과 내용 변경 감지

- 상태: 채택
- 날짜: 2026-09-30
- 출처: #15, [플랜](../plans/15-policy-collection.md)

## 배경

`DEC-DATA`는 원문을 서비스별 최신 1벌과 내용이 바뀐 레코드의 이력만 보관하고, 조회수는 변경 비교에서 빼기로 정했다. 수집·정규화·변경 알림 이슈가 모두 같은 원문과 같은 "내용 변경" 기준을 써야 결과가 어긋나지 않는다.

## 결정

- 원문은 (서비스ID, 경로)마다 `source_record`에 최신 1행을 둔다. 내용이 바뀌면 교체되기 전 원문을 `source_record_history`로 옮긴다. 첫 수집은 이력을 만들지 않는다.
- 내용 변경은 레코드에서 `조회수`를 빼고 키를 정렬해 직렬화한 값의 SHA-256(`content_hash`)으로 판단한다. `ContentHasher`만 이 값을 계산한다.
- 조회수만 바뀐 원문은 최신 행의 원문만 새 값으로 바꾸고, 이력과 `content_changed_at`은 그대로 둔다.
- 원문 컬럼은 MySQL `JSON`이다. `source_record`는 `benefit`에 FK를 걸지 않는다.

## 근거

- 버전 테이블 하나로 두면 최신 원문을 읽을 때마다 최대 버전을 찾아야 한다. 혜택 행에 JSON 컬럼을 두면 목록에 없는 ID의 상세·지원조건 원문을 둘 곳이 없다.
- 해시를 앱에서 계산하면 비교에서 뺄 필드를 한곳에서 관리할 수 있다. MySQL `JSON`은 키 순서와 공백을 바꿔 저장하므로 원문 문자열 비교로는 변경을 판단할 수 없다(로컬 MySQL 8.4에서 `CAST('{"b":1,  "a":2}' AS JSON)`이 `{"a": 2, "b": 1}`로 나오는 것을 확인). 같은 이유로 원본 바이트는 보존하지 않고, 의미가 같은 원문으로 재검증한다.
- 목록 레코드 중 조회수만 수집할 때마다 바뀐다([데이터 검증 보고서 §8](../research/gov24-api-data.md#8-갱신과-삭제)).

## 적용 위치

- `backend/src/main/resources/db/migration/V1__create_benefit_collection_tables.sql`: `source_record`, `source_record_history`
- `backend/src/main/java/kr/govbenefit/collection/service/ContentHasher.java`: 해시 계산과 제외 필드
- `backend/src/main/java/kr/govbenefit/collection/service/CollectionPageWriter.java`: 신규·변경·동일 판단

## 영향

- 원문을 저장하는 수집 경로는 이 테이블과 해시 규칙을 쓴다. 비교에서 뺄 필드를 추가하면 `ContentHasher`만 고치고, 이미 저장된 해시와 달라지는 영향을 함께 다룬다.
- 정규화·변경 알림은 `content_changed_at`과 이력을 내용 변경의 근거로 쓰고, 수집 시각(`collected_at`) 변화는 변경으로 보지 않는다.
- 수집이 실패해도 이미 저장한 최신 원문을 오류나 빈 값으로 덮어쓰지 않는다.
