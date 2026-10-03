status: done

# 구현 계획 — #17 혜택 신청기한·수정 시각 정규화와 빈 값 보존

브랜치: `feat/17-policy-normalization` (develop `a272f2a` 기준)

## 목표 및 범위

- 목표: 목록 원문의 신청기한·수정일시·등록일시를 해석한 값을 혜택에 저장하되, 원문에 없는 값을 만들지 않는다. 해석한 값마다 근거 원문을 찾을 수 있고, 같은 원문은 언제 다시 해석해도 같은 결과가 나온다.
- 포함:
  - `benefit`에 출처 등록·수정 시각과 신청기한 분류(분류, 세부 규칙, 확인 필요) 컬럼 추가(V3 마이그레이션).
  - 신청기한 분류기: #5의 `deadline_classes.sql` 규칙을 Java로 옮기고, 복수 기간 규칙을 더한다.
  - 원문의 빈 문자열·공백 문자열을 `NULL`로 저장.
  - 결정 문서 0008(정규화 값의 저장과 원문 추적).
- 제외:
  - 변환 규칙 버전 관리와 수동 검증 정보(`REQ-DATA-02` P1). 규칙이 바뀌면 다음 수집에서 다시 계산되며, 규칙 변경과 원문 변경을 구분하는 기록은 남기지 않는다.
  - 명시 날짜의 시작·종료일 추출과 기한 경과 판단. 마감 필터·기한 알림 이슈에서 한다.
  - 운영 기관 지역(시도) 추출. `DEC-SCOPE`의 운영 기관 지역 필터는 검색 이슈에서 만든다. 이번에는 지역 컬럼을 만들지 않는다.
  - 지원조건 코드의 구조화와 해석. 조건 비교 이슈(`DEC-MATCH`)에서 한다. 이번에는 #15처럼 원문으로만 둔다.
  - 상세의 수정일시(`yyyy-MM-dd`), 온라인신청 URL 정리, 검색·상세 API에서의 표시.

## 확인된 사실과 제약

- 목록 `수정일시`·`등록일시`는 `yyyyMMddHHmmss`, 상세 `수정일시`는 `yyyy-MM-dd`이고 시간대 표기가 없다. T0에서 목록과 상세 수정일시의 날짜 부분은 모두 같았다([데이터 검증 보고서 §3](../research/gov24-api-data.md#3-세-경로의-연결), [§4 날짜 형식](../research/gov24-api-data.md#날짜-형식)). 결측은 관찰되지 않았지만 형식상 보장이 없다.
- 결측은 모두 JSON `null`이고 공백만 있는 문자열은 T0에서 0건이다. 신청기한은 결측이 없다([§4 결측 비율](../research/gov24-api-data.md#결측-비율)).
- 신청기한 분류(T0): 상시 60.3%, 미확인 27.8%, 명시 날짜 5.7%, 조건부 3.9%, 신청 불필요 2.2%. 규칙 2차 대조 50건 오분류 0건이며 표본은 [집계 결과 파일](../research/gov24-api-data/results/20260928T163852.md)의 "신청기한 분류 검증 표본"에 있다. 명시 날짜 624건 중 42건은 조기 마감 조건이 함께 적혀 있다([§6](../research/gov24-api-data.md#6-신청기한-표현), [부록 B](../research/gov24-api-data.md#부록-b-신청기한-분류-검증)).
- 규칙은 DuckDB `regexp_matches`(부분 일치)로 작성했다. Java `Matcher.find()`와 같은 의미이고, 쓰인 문법(`\s`, `\d`, 문자 클래스, 선택)은 두 엔진에서 같다.
- 대상 지역 필드는 원천에 없다. 지원조건 코드는 `"Y"` 또는 `null`이며, 개인 코드 전부 `null`은 표본 3건 모두 개인 대상이 아니었다(`DEC-SCOPE`, `DEC-MATCH`). 따라서 이번 이슈에서 지역·조건을 채우는 코드 경로를 새로 만들지 않는 것이 빈 값 보존의 핵심이다.
- 현재 구조(#15, #16): `CollectionPageWriter.write`가 쪽 하나를 한 트랜잭션으로 저장하고, 목록 쪽에서는 `source_record`(경로 `SERVICE_LIST`)와 `benefit`을 같은 트랜잭션에서 쓴다. `Benefit.confirm`은 `BenefitContent`가 다를 때만 내용과 `updated_at`을 바꾼다. `Gov24RecordMapper.text`는 JSON `null`을 `null`로, 문자열은 그대로 옮긴다.
- 원문은 `SourceRecordRepository.findByServiceIdAndEndpoint(serviceId, SERVICE_LIST)`로 조회한다. 내용이 바뀐 이전 원문은 `source_record_history`에 있다([0003](../decisions/0003-source-record-retention.md)).
- 시각 컬럼은 `Asia/Seoul` 기준이다([0002](../decisions/0002-db-timezone.md)). 출처 시각이 한국 시각이라는 근거는 없으며, 같은 시간대로 해석한다는 가정이다.
- API는 원천 수정을 다음 날 반영하는 것으로 관찰되고 있다(#6 진행 중). 출처 수정 시각이 실제 수정 시점보다 늦게 보이는 것은 아니고 늦게 수집될 뿐이라, 이번 저장 방식에는 영향이 없다.

## 설계 결정

1. **정규화 값은 `benefit` 컬럼에 두고, 목록 원문을 저장하는 같은 트랜잭션에서 계산한다.** 결정 문서 0008.
   - 대안 B(별도 1:1 테이블에 근거 원문 해시 저장)는 원문과 해석을 테이블 단위로 분리하고 P1 규칙 버전에 유리하지만, P0에는 엔티티·조인이 늘어날 뿐이다. 같은 트랜잭션에서 계산하므로 `benefit`의 정규화 값은 항상 `source_record(service_id, SERVICE_LIST)`의 현재 원문에서 나온 값이고, 근거 원문을 따로 가리킬 필요가 없다.
   - 대안 C(수집 뒤 별도 정규화 단계)는 원문만으로 재정규화할 수 있지만 실패·실행 이력 장치가 또 필요하다. 대안 D(조회 시 계산)는 저장하지 않아 완료 조건과 맞지 않고 마감 필터를 DB에서 처리할 수 없다.
   - 코드에서는 원문 값(`BenefitContent`)과 해석 값(`BenefitNormalization`)을 다른 레코드로 나눠, 테이블은 같아도 어느 컬럼이 해석인지 드러나게 한다.
   - 매일 전체 수집에서 모든 목록 레코드를 다시 계산하므로, 규칙을 고치거나 마이그레이션 직후 `NULL`인 행은 다음 수집에서 채워진다. 별도 백필은 두지 않는다. 해석 값이 바뀌면 `updated_at`도 바뀌며, 이것이 원문 변경인지 규칙 변경인지는 `source_record.content_changed_at`과 비교해야 알 수 있다(구분 기록은 P1).
2. **정규화 컬럼마다 근거 원문 필드는 하나로 고정한다.**

   | 컬럼 | 근거 원문 |
   | --- | --- |
   | `source_registered_at` | 목록 `등록일시` |
   | `source_modified_at` | 목록 `수정일시` |
   | `apply_period_type`, `apply_period_rule`, `apply_period_needs_review` | 목록 `신청기한`(`apply_period_text`에 원문 그대로 있음) |

   원문 조회는 `benefit.service_id`로 `source_record(service_id, SERVICE_LIST)`를 찾는다. 이 대응은 결정 문서와 `BenefitNormalization` Javadoc에 둔다.
3. **출처 시각은 `uuuuMMddHHmmss`를 `ResolverStyle.STRICT`로 해석하고, 없거나 형식이 다르거나 존재하지 않는 시각이면 `NULL`로 둔다.** 수집 시각·마지막 확인 시각으로 대신 채우는 경로를 만들지 않는다. 원문 문자열은 `source_record`에 있으므로 별도 원문 컬럼은 두지 않는다. 상세 `수정일시`는 날짜만 있고 목록과 같으므로 쓰지 않는다.
4. **신청기한은 다섯 분류(`ApplyPeriodType`)와 세부 규칙(`ApplyPeriodRule`)으로 저장하고, 확인 필요는 분류와 별개의 플래그로 둔다.** 분류는 이슈 결정(2026-10-03)에 따라 명시 날짜·상시·조건부·신청 불필요·미확인이다.
   - 규칙은 위에서부터 처음 맞는 하나를 쓰며 `deadline_classes.sql`의 순서를 그대로 따르고, 맨 앞쪽에 복수 기간 규칙 하나를 더한다. 확인 필요 여부는 세부 규칙이 정하며, 엔티티는 규칙에서 분류와 플래그를 함께 채운다.

   | `ApplyPeriodRule` | 분류 | 확인 필요 | SQL 규칙 |
   | --- | --- | --- | --- |
   | `NO_VALUE` | 미확인 | 아니오 | 미확인:값 없음 |
   | `NOT_REQUIRED` | 신청 불필요 | 아니오 | 신청 불필요:신청 절차 없음·자동 지급 |
   | `MULTIPLE_PERIODS` | 명시 날짜 | 예 | (추가) |
   | `DATE_WITH_EARLY_CLOSE` | 명시 날짜 | 예 | 명시 날짜:연월일 (조기 마감 조건 포함) |
   | `DATE` | 명시 날짜 | 아니오 | 명시 날짜:연월일 |
   | `EVENT_BASED` | 조건부 | 아니오 | 조건부:사건 기준 기한 |
   | `BUDGET_OR_FIRST_COME` | 조건부 | 아니오 | 조건부:예산 소진·선착순 |
   | `ALWAYS` | 상시 | 아니오 | 상시:상시·연중·수시 |
   | `YEAR_MONTH_ONLY` | 미확인 | 아니오 | 미확인:연월만 명시 |
   | `NONE_STATED` | 미확인 | 아니오 | 미확인:없음·해당 없음 |
   | `VARIES_BY_AGENCY` | 미확인 | 아니오 | 미확인:기관·사업별 상이 |
   | `SEE_NOTICE` | 미확인 | 아니오 | 미확인:공고·별도 안내·문의 |
   | `RECURRING` | 미확인 | 아니오 | 미확인:반복 시기 (연도·날짜 없음) |
   | `OTHER` | 미확인 | 예 | 미확인:기타 |

   - 복수 기간: 신청 불필요가 아니고 연월일이 있으면서, 기간 구분자(`~`, `∼`, `〜`, `～`)가 2개 이상이거나 연월일이 3개 이상인 문구. `2026.01.19~2026.12.18`(연월일 2개, 구분자 1개)은 단일 기간이다. 분류는 명시 날짜 그대로라 #5 분류 비율은 바뀌지 않는다.
   - 확인 필요는 "분류는 했지만 원문을 함께 봐야 하는 경우"로 한정한다. 복수 기간, 날짜와 조기 마감이 섞인 문구(마감일을 알 수 없음), 어느 규칙에도 맞지 않은 문구다. 미확인의 다른 세부 규칙은 "기한을 알 수 없다"는 것 자체가 확인된 결과이므로 플래그를 세우지 않는다. 모든 미확인에 플래그를 세우면 플래그가 미확인과 같은 뜻이 된다.
   - 대안: 정확 일치 문구표(범위가 좁음), LLM 분류(결과가 고정되지 않고 외부 서비스에 의존). 정규식 규칙은 #5에서 검증됐고 같은 입력에 같은 결과를 낸다.
5. **원문 문자열이 빈 값이거나 공백뿐이면 `NULL`로 저장한다.** `Gov24RecordMapper.text` 한 곳에서 처리하므로 `benefit`·`benefit_detail`의 모든 문자열 컬럼에 적용된다. 그 외 문자열은 앞뒤 공백도 그대로 둔다. 빈 값을 다른 문구로 바꾸지 않으며, 원문은 `source_record`에 그대로 남는다.

## 단계별 구현 계획

1. **마이그레이션** `backend/src/main/resources/db/migration/V3__add_benefit_normalization.sql`
   - `benefit`에 `source_registered_at DATETIME NULL`, `source_modified_at DATETIME NULL`, `apply_period_type VARCHAR(20) NULL`, `apply_period_rule VARCHAR(30) NULL`, `apply_period_needs_review BOOLEAN NULL`을 추가한다. 출처 시각은 초 단위라 `DATETIME`(정밀도 0)이다.
   - 기존 행은 `NULL`이며 다음 수집에서 채워진다(설계 1). 인덱스는 검색 이슈에서 정한다.
2. **도메인** `kr.govbenefit.benefit.domain`
   - `ApplyPeriodType` enum: `FIXED_DATE`, `ALWAYS`, `CONDITIONAL`, `NOT_REQUIRED`, `UNKNOWN`.
   - `ApplyPeriodRule` enum: 설계 4의 표. 필드 `type`, `needsReview`.
   - `BenefitNormalization` record(`LocalDateTime sourceRegisteredAt`, `LocalDateTime sourceModifiedAt`, `ApplyPeriodRule applyPeriodRule`): Javadoc에 설계 2의 근거 원문 대응을 적는다.
   - `Benefit`: 위 다섯 컬럼 필드 추가. `create(serviceId, content, normalization, now)`, `confirm(content, normalization, now)`는 둘 중 하나라도 다를 때만 내용과 `updated_at`을 바꾼다. `normalization()` 추가. `setNormalization`이 규칙에서 분류·플래그를 채운다.
   - `BenefitContent` Javadoc에 빈 문자열은 `null`로 둔다는 것을 더한다.
3. **분류기** `kr.govbenefit.collection.service.ApplyPeriodClassifier`
   - `static ApplyPeriodRule classify(String text)`: `null`이면 `NO_VALUE`. 규칙별 `Pattern`을 상수로 컴파일하고 설계 4의 순서로 `find()`한다. 상시 규칙은 `접수시`를 지운 문자열로 매칭한다(SQL과 같음).
   - 복수 기간 판정은 연월일 패턴(`(19|20)\d{2}\s*[.\-/년]\s*\d{1,2}\s*[.\-/월]\s*\d{1,2}`)의 출현 수와 기간 구분자 수로 한다.
4. **매퍼** `Gov24RecordMapper`
   - `text`: 문자열이 `isBlank()`면 `null`.
   - `toBenefitNormalization(JsonNode record)`: `dateTime(record, "등록일시")`, `dateTime(record, "수정일시")`, `ApplyPeriodClassifier.classify(text(record, "신청기한"))`.
   - `dateTime`: 문자열이 아니거나 형식이 맞지 않으면 `null`. 예외를 밖으로 던지지 않는다.
5. **쓰기 경로** `CollectionPageWriter.saveBenefits`: `Benefit.create`·`confirm`에 `Gov24RecordMapper.toBenefitNormalization(record)`를 함께 넘긴다.
6. **테스트**
   - `ApplyPeriodClassifierTest`(단위, `@ParameterizedTest`): 검증 표본 50건(분류별 10건)의 서비스ID·원문·기대 분류·기대 세부 규칙. 1차 오분류 2건(`323000000114`, `569000000340`)이 미확인인지. 복수 기간 예(`1차 2026.3.2.~3.13., 2차 2026.9.1.~9.12.` 등 합성 문구), 단일 기간 반례(`2026.01.19~2026.12.18`, `2026. 3. 23.(월) 09:00 ~ 12. 11.(금) 18:00`), 기타 예(`무인민원발급기 운영시간`), `null`.
   - `Gov24RecordMapperTest`(단위): 등록·수정일시 정상 해석, `null`·`"2026-09-14"`·`"20260230120000"`이면 `null`. 빈 문자열·공백 문자열이 `null`이고 앞뒤 공백이 있는 값은 그대로인지.
   - `BenefitNormalizationTest`(새 클래스, `@SpringBootTest`, Testcontainers, 별도 stub, `per-page=2`, `schedule.enabled=false`): 시나리오는 완료 조건 매핑에 적는다. `setUp`은 `CollectionServiceTest`와 같은 테이블을 비운다.
7. **결정 문서** `docs/decisions/0008-benefit-normalization.md`: 설계 1~5(저장 위치와 계산 시점, 근거 원문 대응과 조회 방법, 출처 시각을 수집 시각으로 대신하지 않음, 신청기한 분류·확인 필요의 의미, 빈 값 `NULL`과 기본값 금지). 영향에 검색·상세(수집 시각과 출처 수정 시각을 구분해 표시), 조건 비교(빈 조건을 '누구나'로 해석하지 않음), 변경 알림(해석 값 변화만으로 알리지 않음)을 적는다. `docs/decisions/README.md` 목록 표에 추가한다.

## 완료 조건 매핑

`BenefitNormalizationTest`의 stub fixture(A·B·C)는 각 시나리오 전에 `stub.record(endpoint, serviceId)`로 바꾼다.

- [ ] 출처 수정 시각·등록 시각이 없는 혜택에 수집 시각이 수정 시각·등록 시각으로 들어가지 않는다. → 설계 3, 4·6단계. B의 `수정일시`·`등록일시`를 `null`, C의 `수정일시`를 `"2026-08-12"`로 두고 수집: B의 두 컬럼과 C의 `source_modified_at`이 `NULL`, A는 `2026-09-14T11:43:46`. 단위 테스트에서 형식 오류·존재하지 않는 시각.
- [ ] #5 표본의 신청기한 문구가 다섯 분류(명시 날짜·상시·조건부·신청 불필요·미확인) 중 하나로 나뉘고, 분류별 예시가 테스트에 있다. → 설계 4, 3·6단계. `ApplyPeriodClassifierTest`의 검증 표본 50건(분류별 10건)과 1차 오분류 2건.
- [ ] 복수 기간이나 애매한 문구는 원문과 함께 확인 필요 상태로 저장된다. → 설계 4, 3·5·6단계. A의 `신청기한`을 복수 기간 문구, B를 `2026. 4. 1.~ 예산 소진 시`, C를 `무인민원발급기 운영시간`으로 두고 수집: 세 혜택 모두 `apply_period_needs_review = true`, 세부 규칙이 각각 `MULTIPLE_PERIODS`·`DATE_WITH_EARLY_CLOSE`·`OTHER`이고 `apply_period_text`가 원문과 같다.
- [ ] 지역·조건이 빈 혜택은 빈 값으로 저장되고, ‘전국’이나 ‘누구나’로 저장되지 않는다. → 설계 5, 4·6단계. B의 목록 `소관기관명`·`선정기준`을 `null`, `지원대상`을 `"  "`, 상세 `자치법규`를 `""`, 지원조건 코드를 모두 `null`로 두고 수집: 해당 `benefit`·`benefit_detail` 컬럼이 `NULL`이고, B의 `benefit`·`benefit_detail` 어느 문자열 컬럼에도 `전국`·`누구나`가 없으며, 지원조건 `source_record` 원문의 코드 값이 `null` 그대로다. 지역 컬럼은 만들지 않는다(목표 및 범위의 제외).
- [ ] 정규화된 혜택에서 해당 원문을 조회할 수 있다. → 설계 2, 7단계. 각 혜택의 `service_id`로 `findByServiceIdAndEndpoint(serviceId, SERVICE_LIST)`가 원문을 돌려주고, 그 원문의 `등록일시`·`수정일시`·`신청기한`이 설계 2의 대응대로 정규화 컬럼과 맞는다.
- [ ] 같은 원문을 다시 정규화하면 결과가 같다. → 설계 1, 5·6단계. 같은 stub으로 두 번 수집: 정규화 컬럼과 `updated_at`이 첫 수집 후와 같다. 저장된 원문을 `Gov24RecordMapper.toBenefitNormalization`에 다시 넣은 결과가 `Benefit.normalization()`과 같다. 정규화 컬럼을 JDBC로 `NULL`로 지운 뒤 다시 수집하면 첫 수집과 같은 값으로 채워진다.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 실제 데이터 확인(기본 테스트에 포함하지 않음): 로컬에서 `SPRING_PROFILES_ACTIVE=local,collect`로 1회 수집한 뒤 분류 비율을 #5와 비교한다. 복수 기간으로 옮겨진 건수만큼 `DATE`·`DATE_WITH_EARLY_CLOSE`가 줄고 분류별 합계는 #5와 같아야 한다(T0 이후 원문 변경분 제외).
  ```sql
  SELECT apply_period_type, apply_period_rule, apply_period_needs_review, COUNT(*)
  FROM benefit GROUP BY 1, 2, 3 ORDER BY 1, 2;
  SELECT COUNT(*) FROM benefit WHERE source_modified_at IS NULL OR source_registered_at IS NULL;
  ```

## 결정 필요

없음
