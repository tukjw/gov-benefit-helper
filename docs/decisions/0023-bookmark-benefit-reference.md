# 0023. 북마크의 혜택 참조

- 상태: 채택
- 날짜: 2026-10-20
- 출처: #56, [플랜](../plans/56-saved-benefits.md)

## 배경

`REQ-SAVE-01`은 관심 목록(코드 이름은 북마크)에 혜택의 현재 정보를 보여주고, 관심 기록을 임의로 삭제하지 않으며, 출처 조회 실패를 혜택 종료로 보지 않으라고 한다. API는 혜택을 서비스 ID로 가리킨다(`DEC-SEARCH`). #56에서 북마크 행이 혜택을 무엇으로 참조하고, 혜택 행이 사라질 때 북마크를 어떻게 할지 정해야 했다.

## 결정

- 북마크(`bookmark`)는 `benefit_id`로 `benefit.id`를 참조하고, 이 FK에는 `ON DELETE` 동작을 두지 않는다(RESTRICT).
- 북마크가 달린 혜택 행은 지우지 않는다. 출처 목록에서 사라진 혜택은 행을 남기고 `last_seen_at`이 멈춘다.

## 근거

- `benefit_detail`과 같은 방식이고, DB가 혜택의 존재를 보장한다. 혜택 행을 지우려 하면 FK가 막으므로 "관심 기록을 임의로 삭제하지 않는다"를 DB가 지킨다.
- `CASCADE`를 두면 혜택 행 삭제가 사용자의 북마크를 말없이 지운다.
- 대안: `service_id` 문자열, FK 없음. API 식별자와 같고 혜택 행을 다시 만들어도 연결이 유지되지만, 존재 검사를 애플리케이션만 하고 고아 기록을 DB가 모른다.
- 대안: `service_id` → `benefit.service_id`(UNIQUE) FK. 무결성은 같지만 저장소에서 처음 쓰는 방식이고 VARCHAR FK다.
- 수집은 지금 `benefit` 행을 지우지 않는다(`CollectionPageWriter`). 그래서 이 제약은 현재 동작을 바꾸지 않는다.

## 적용 위치

- `backend/src/main/resources/db/migration/V10__create_bookmark.sql`: `fk_bookmark_benefit`
- `backend/src/main/java/kr/govbenefit/bookmark/service/BookmarkService.java`: 목록이 모든 북마크에 혜택 행이 있다고 보고 조회한다.
- `backend/src/test/java/kr/govbenefit/bookmark/controller/BookmarkApiTest.java`: 수집 목록에서 사라지거나 수집이 실패해도 북마크가 남는다.

## 영향

- 혜택 비노출·종료(`REQ-DATA-01` P1, `REQ-SAVE-01` P1)와 알림(3차)은 혜택 행 삭제가 아니라 상태로 다룬다.
- 혜택 행을 지워야 하는 작업이 생기면 이 결정을 대체한다.
- 테스트에서 `benefit`을 비울 때는 `bookmark`를 먼저 비운다.
