# 0008. 혜택 정규화 값의 저장과 원문 추적

- 상태: 채택
- 날짜: 2026-10-03
- 출처: #17, [플랜](../plans/17-policy-normalization.md)

## 배경

검색·상세에서 신청기한과 수정 시각을 보여주려면 원문을 해석해야 한다. `REQ-DATA-02`는 해석 결과에서 원문을 찾아갈 수 있어야 하고, 수집 시각을 출처 수정 시각처럼 보여주거나 빈 지역·조건을 ‘전국’·‘누구나’로 바꾸지 말라고 정했다. 검색·상세·조건 비교·변경 알림이 모두 같은 해석 값과 같은 빈 값 규칙을 써야 결과가 어긋나지 않는다.

## 결정

- 해석 값은 `benefit` 컬럼에 두고, 목록 원문(`source_record`, 경로 `SERVICE_LIST`)을 저장하는 같은 트랜잭션에서 계산한다. 따라서 해석 값의 근거는 항상 같은 서비스ID의 현재 목록 원문이다.
- 해석 컬럼마다 근거 원문 필드는 하나로 고정한다.

  | 컬럼 | 근거 원문 |
  | --- | --- |
  | `source_registered_at` | 목록 `등록일시` |
  | `source_modified_at` | 목록 `수정일시` |
  | `apply_period_type`, `apply_period_rule`, `apply_period_needs_review` | 목록 `신청기한`(`apply_period_text`) |

- 출처 시각은 `yyyyMMddHHmmss`를 엄격하게 해석하고, 없거나 해석할 수 없으면 `NULL`로 둔다. 수집 시각·마지막 확인 시각으로 채우지 않는다.
- 신청기한은 다섯 분류(`ApplyPeriodType`: 명시 날짜·상시·조건부·신청 불필요·미확인)와 세부 규칙(`ApplyPeriodRule`)으로 저장한다. 분류와 확인 필요 여부는 세부 규칙이 정한다. 확인 필요는 분류는 했지만 원문을 함께 봐야 하는 경우(복수 기간, 날짜와 조기 마감이 섞인 문구, 어느 규칙에도 맞지 않은 문구)에만 둔다.
- 원문의 빈 문자열과 공백뿐인 문자열은 `NULL`로 저장하고, 빈 값을 다른 문구나 기본값으로 바꾸지 않는다. 대상 지역 컬럼은 두지 않고, 지원조건 코드는 원문으로만 둔다.
- 해석 규칙은 `ApplyPeriodClassifier`와 `Gov24RecordMapper.toBenefitNormalization`에만 둔다.

## 근거

- 원문 저장과 같은 트랜잭션에서 계산하면 근거 원문을 따로 가리키지 않아도 해석 값과 원문이 어긋나지 않는다. 매일 전체 수집에서 모든 목록 레코드를 다시 계산하므로 규칙을 고치면 다음 수집에 반영된다.
- 제외한 대안: 별도 1:1 테이블에 근거 원문 해시를 두는 방식(원문·해석 분리와 규칙 버전에는 유리하지만 `REQ-DATA-02` P0에는 엔티티와 조인만 늘어난다), 수집 뒤 별도 정규화 단계(실패·실행 이력 장치가 또 필요하다), 조회 시 계산(마감 필터·정렬을 DB에서 처리할 수 없다).
- 신청기한 규칙은 #5에서 원문과 대조해 2차 표본 50건 오분류 0건이었다([데이터 검증 보고서 §6](../research/gov24-api-data.md#6-신청기한-표현)). ‘신청 불필요’(T0 2.2%)는 신청 절차가 없다는 확인된 정보라 미확인과 구분한다. 모든 미확인에 확인 필요를 두면 플래그가 미확인과 같은 뜻이 된다.
- 원천에 대상 지역 필드가 없고, 지원조건 코드가 모두 `null`인 혜택은 표본에서 개인 대상이 아니었다(`DEC-SCOPE`, `DEC-MATCH`). 빈 값을 채우면 원문에 없는 조건을 만든다.

## 적용 위치

- `backend/src/main/resources/db/migration/V3__add_benefit_normalization.sql`: 해석 컬럼
- `backend/src/main/java/kr/govbenefit/collection/service/ApplyPeriodClassifier.java`: 신청기한 규칙
- `backend/src/main/java/kr/govbenefit/collection/service/Gov24RecordMapper.java`: 출처 시각 해석, 빈 문자열 `NULL`
- `backend/src/main/java/kr/govbenefit/benefit/domain/ApplyPeriodRule.java`: 규칙별 분류와 확인 필요
- `backend/src/main/java/kr/govbenefit/collection/service/CollectionPageWriter.java`: 원문과 같은 트랜잭션에서 저장

## 영향

- 검색·상세는 출처 수정 시각과 수집 시각(`last_seen_at`, `source_record.collected_at`)을 구분해 표시하고, 출처 시각이 `NULL`이면 수집 시각으로 대신하지 않는다. 확인 필요인 신청기한은 원문(`apply_period_text`)을 함께 보여준다.
- 운영 기관 지역을 만들 때(검색 이슈) 소관기관명에서 읽을 수 없으면 `NULL`로 두고, 대상 지역으로 쓰지 않는다.
- 조건 비교는 빈 조건이나 코드 전부 `null`을 ‘누구나’로 해석하지 않는다.
- 해석 값이 바뀌면 `benefit.updated_at`도 바뀐다. 변경 알림은 원문 변경을 `source_record.content_changed_at`과 이력으로 판단하고, 해석 값만 바뀐 경우를 원문 변경으로 보지 않는다. 규칙 변경 기록은 `REQ-DATA-02` P1에서 정한다.
- 해석 규칙을 바꾸면 #5 표본 테스트(`ApplyPeriodClassifierTest`)를 함께 고치고, 분류 비율이 바뀌는 영향을 확인한다.
