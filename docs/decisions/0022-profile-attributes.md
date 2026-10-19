# 0022. 프로필 속성 값과 미입력 표현

- 상태: 채택
- 날짜: 2026-10-17
- 출처: #49, [플랜](../plans/49-user-profile.md)

## 배경

`DEC-PROFILE`은 가구·생애 속성을 여러 개 선택하고 '해당 없음'을 포함하며, 모든 항목을 선택 입력으로 받는다고 정했다. `REQ-PROFILE-01`은 미입력과 '해당 없음'을 구분하라고 한다. 이후 조건 비교(`REQ-MATCH-01`)는 이 값을 혜택의 지원조건 코드(생애·직업·건강 `JA03xx`, 가구 `JA04xx`)와 비교한다. #49에서 API 값, 미입력 표현, 저장 형식을 정해야 했다.

## 결정

- API는 그룹별 필드 `lifeAttributes`(생애·직업·건강)와 `householdAttributes`(가구)로 주고받는다.
  - `null`: 미입력.
  - `["NONE"]`: 해당 없음.
  - 그 밖: 고른 속성 목록.
  - 빈 배열, 같은 값의 중복, `null` 원소, 다른 그룹의 값, 같은 그룹에서 `NONE`과 다른 값을 함께 보내는 입력은 거절한다.
- API 값은 자체 enum 이름(`PREGNANT` 등)이고, 값마다 지원조건 코드 하나가 대응한다. `NONE`은 생애 `JA0322`, 가구 `JA0410`이다. 대응표는 `LifeAttribute`·`HouseholdAttribute`에 있다.
- 저장은 지원조건 코드로 한다. `user_profile_attribute`(`user_id`, `condition_code`)에 고른 속성 하나당 한 행이며 `NONE`도 한 행이다. 그룹의 코드가 하나도 없으면 미입력이다. `CHECK` 제약으로 26개 코드만 허용한다.
- 응답의 속성 목록은 enum 선언 순서이고 `NONE`이 마지막이다.

## 근거

- 빈 배열을 '해당 없음'으로 쓰면 값이 하나 줄지만, 클라이언트가 실수로 보낸 빈 배열이 '해당 없음'으로 저장되고 "해당 없음과 다른 속성을 함께 고른 입력"을 표현할 수 없다.
- 그룹마다 `{status, values}`를 두면 상태가 가장 명시적이지만 응답이 장황하고 상태와 값의 모순 검증이 늘어난다.
- JA 코드를 API 값으로 노출하면 매핑이 없지만, API가 원천 코드 체계에 묶이고 읽기 어렵다. API 값과 저장값을 나누어 API는 읽기 쉽게, 저장은 원천과 같게 했다.
- 코드로 저장하면 조건 비교가 혜택의 원문 코드와 바로 비교하고, enum 이름을 바꿔도 데이터를 옮기지 않는다. JA 코드는 두 그룹 사이에 겹치지 않아 그룹 컬럼이 필요 없다.
- 대안: JSON 컬럼. 한 행이라 단순하지만 값 무결성을 DB가 막지 못하고, 알림 대상 선정에서 "속성 X를 가진 사용자"를 찾으려면 MySQL 전용 함수나 멀티밸류 인덱스가 필요하다.
- 대안: `SET` 타입·비트마스크. 값을 추가할 때 ALTER와 순서 고정이 필요하다.
- 생애·직업·건강 코드는 번호대로 가족·출산, 직업, 학생, 건강·보훈으로 나뉘어 보이지만 해당사항없음(`JA0322`)이 하나뿐이라 한 그룹으로 둔다([보고서 §5](../research/gov24-api-data.md#5-지원조건-코드)).

## 적용 위치

- `backend/src/main/resources/db/migration/V9__create_user_profile.sql`: `user_profile_attribute`와 `CHECK` 제약
- `backend/src/main/java/kr/govbenefit/profile/domain/LifeAttribute.java`, `HouseholdAttribute.java`, `ProfileAttribute.java`: 값과 코드의 대응
- `backend/src/main/java/kr/govbenefit/profile/domain/UserProfile.java`: 코드 저장과 그룹별 복원
- `backend/src/main/java/kr/govbenefit/profile/service/ProfileValidator.java`: 입력 규칙
- `backend/src/test/java/kr/govbenefit/profile/domain/ProfileAttributeTest.java`, `profile/controller/ProfileApiTest.java`

## 영향

- 조건 비교(`REQ-MATCH-01`)는 `user_profile_attribute.condition_code`를 혜택의 지원조건 코드와 비교한다. 그룹이 미입력이면 미입력으로 다루고 불일치로 처리하지 않는다.
- 원천에 생애·가구 코드가 추가되면 enum, `CHECK` 제약, 마이그레이션을 함께 바꾼다.
- 새 여러 선택 항목을 프로필에 더하면 같은 미입력·`NONE` 표현을 쓴다.
