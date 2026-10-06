# 0010. API 응답 시각 형식

- 상태: 채택
- 날짜: 2026-10-04
- 출처: #29, [플랜](../plans/29-benefit-search-api.md)

## 배경

DB와 애플리케이션은 `Asia/Seoul` 기준 `LocalDateTime`으로 시각을 다룬다([0002](0002-db-timezone.md)). 그대로 직렬화하면 응답에 오프셋이 없어, 클라이언트가 한국 시각이라는 것을 따로 알아야 한다. #29의 마지막 확인 시각, #30의 출처 수정 시각·수집 시각·오래된 정보 기준 시각이 같은 형식을 써야 한다.

## 결정

API 응답의 시각은 `+09:00` 오프셋을 붙인 ISO-8601 문자열로 보낸다. 예: `2026-10-04T04:00:12.345+09:00`. 엔티티는 `LocalDateTime` 그대로 두고, 응답 DTO로 바꿀 때 `ApiTimes.toOffset`으로 `Asia/Seoul` 기준 `OffsetDateTime`을 만든다.

## 근거

- 오프셋이 있으면 앱이나 다른 시간대의 클라이언트도 시각을 다르게 해석하지 않는다.
- 오프셋 없는 문자열은 변환이 없지만, 시간대 약속이 응답 밖에 있어 놓치기 쉽다.
- UTC(`Z`)로 바꿔 보내는 방법도 있지만, 저장·로그·문서가 모두 한국 시각이라 응답만 UTC면 대조할 때 헷갈린다.
- 소수 초는 저장된 정밀도대로 나가며 0이면 생략된다. 클라이언트는 ISO-8601 파서로 읽어야 한다.

## 적용 위치

- `backend/src/main/java/kr/govbenefit/common/api/ApiTimes.java`: 변환과 API 명세의 시각 예시(`EXAMPLE`, #33, [0012](0012-api-spec-docs.md))
- `backend/src/main/java/kr/govbenefit/benefit/dto/BenefitSummary.java`: `lastSeenAt`

## 영향

- 시각을 내보내는 응답 DTO의 필드는 `OffsetDateTime`으로 두고 `ApiTimes.toOffset`으로 만든다. `LocalDateTime`을 응답에 그대로 두지 않는다.
- 값이 없는 시각(예: 출처 수정 시각이 없음)은 `null`이며 다른 시각으로 채우지 않는다([0008](0008-benefit-normalization.md)).
- 날짜만 있는 값(시각이 없는 값)은 이 결정의 대상이 아니다. 생기면 따로 정한다.
