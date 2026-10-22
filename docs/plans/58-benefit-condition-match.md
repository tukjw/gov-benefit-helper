status: done

# 구현 계획 — #58 혜택 상세에서 내 프로필과 지원조건 비교 결과 제공

브랜치: `feat/58-benefit-condition-match` (develop `6122ca8` 기준)

## 목표 및 범위

- 목표: 로그인한 사용자가 혜택 하나에 대해 자기 프로필과 지원조건 코드를 비교한 결과(‘부합’·‘확인 필요’·‘불일치’), 항목별 판정·이유·비교한 값, 원문과 공식 링크를 받는다(`REQ-MATCH-01` P0 중 혜택 하나의 비교, `DEC-MATCH`).
- 포함:
  - `GET /api/me/matches/{serviceId}`(200).
  - 새 도메인 패키지 `kr.govbenefit.match`. 판정은 DB를 모르는 순수 도메인 객체가 한다.
  - 개인 대상 여부의 판정 기준을 정하고 `DEC-MATCH`에 기록한다. 연령 0·120, 해당사항없음 단독, 불일치일 때 나머지 항목의 처리도 함께 기록한다.
  - API 명세, `ApiDocsTest`.
- 제외(이슈 본문):
  - 비교 결과 순 목록·정렬(#59).
  - 비교 항목 추가(소득 등).
  - 소상공인·창업 단계 비교(#60).
  - 알림 대상 선정(3차).
  - 혜택 상세 응답 변경. 비로그인 상세는 지금처럼 동작한다.
  - 지원조건 코드의 정규화 저장. 필요하면 #59의 측정 결과로 별도 이슈를 만든다(설계 2).

## 확인된 사실과 제약

- 지원조건 코드는 `source_record`(경로 `SUPPORT_CONDITIONS`)에 원문 JSON으로만 있다. 수집기는 이 경로를 혜택과 연결하지 않고 연결되지 않은 건수만 센다(`CollectionPageWriter.write`). 결정 0008은 "지원조건 코드는 원문으로만 둔다", "조건 비교는 빈 조건이나 코드 전부 `null`을 ‘누구나’로 해석하지 않는다"고 정했다.
- 원문 값: `JA0110`·`JA0111`(연령 시작·종료)은 정수 또는 `null`이다. 나머지 46개 코드는 문자열 `"Y"` 또는 `null`이다([보고서 §5](../research/gov24-api-data.md#5-지원조건-코드)). 조회는 `SourceRecordRepository.findByServiceIdAndEndpoint`로 한다.
- 코드의 의미([보고서 §5 확인한 의미](../research/gov24-api-data.md#확인한-의미)):
  - `Y`는 "그 속성이 있는 사람도 대상에 포함된다"는 뜻이다.
  - 그룹 전부 `Y`이면 제한이 없다.
  - 해당사항없음(`JA0322`, `JA0410`)만 `Y`인 경우도 제한이 없다(4/4).
  - 연령 0~120이면 제한이 없다. 경계 포함 여부는 확인하지 못했다.
- 프로필:
  - 생애·가구 속성은 JA 코드로 저장되고, 그룹에 코드가 없으면 미입력, `NONE`은 해당사항없음 코드 한 행이다(결정 0022).
  - 성별은 `MALE`·`FEMALE`이고 각각 `JA0101`·`JA0102`에 대응한다(`Gender`).
  - 만 나이는 `Ages.fullAge(birthDate, today)`로 계산한다.
  - 프로필을 저장한 적이 없으면 행이 없으며, 모든 값이 미입력인 것과 같게 다룬다(`ProfileService.get` 주석).
- 사용자구분(`benefit.user_type`)은 목록 원문을 `||`로 이어 붙인 값이다. 값은 `개인`, `가구`, `소상공인`, `법인/시설/단체` 네 종류이고 빈 값은 없다.
- 로컬 수집 데이터(2026-10-21, 10,950건)로 개인 대상 판정 기준을 교차 집계한 결과:

  | | 개인 코드(`JA01`~`JA04`) 전부 `null` | 일부 있음 |
  | --- | --- | --- |
  | 사용자구분에 `개인`·`가구` 있음 | 8 | 9,928 |
  | 사용자구분에 `개인`·`가구` 없음 | 959 | 55 |

  - 사용자구분에 `개인`·`가구`가 없는 1,014건은 `법인/시설/단체` 717건, `소상공인` 232건, `소상공인||법인/시설/단체` 65건이다.
  - 두 기준이 엇갈린 63건을 표본으로 보면 어느 쪽도 일관되지 않다. 코드가 전부 `null`인 개인 구분 혜택에는 실제 개인 대상 혜택(현장체험학습비·유아학비)이 있다. 코드가 있는 법인 구분 혜택은 대부분 기관 대상이다.
- 인증 경계: `/api/benefits/**`는 공개 경로이고 `/api/me/**`는 공개 경로가 아니다. 사용자 ID는 `@CurrentUserId`로만 받는다. 액세스 토큰 검증은 DB를 보지 않으므로, 개인 API는 계정이 없으면 401 `UNAUTHENTICATED`를 낸다(결정 0018, `BookmarkService.requireAccount`).
- 테스트 stub fixture에는 세 서비스(`511000000138`, `403000000173`, `O00024000031`)가 세 경로에 모두 있고, 사용자구분은 모두 `개인`이다. `Gov24StubServer.record(endpoint, serviceId)`로 레코드를 바꾸고, `override`로 경로·쪽별 실패를 넣을 수 있다.
- 이번 이슈는 테이블을 더하지 않으므로, 혜택을 지우는 기존 테스트의 정리 순서는 바뀌지 않는다.

## 설계 결정

1. **개인 대상 여부는 사용자구분으로 판정한다.** 2026-10-21 사용자 결정. → `DEC-MATCH`에 기록
   - `||`로 나눈 값에 `개인` 또는 `가구`가 있으면 개인 대상(충족)이다. 둘 다 없으면 개인 대상이 아니다(‘불일치’). 사용자구분이 비어 있으면 데이터 누락이므로 ‘확인 필요’다.
   - 지원조건 코드는 보지 않는다. 그래서 지원조건이 수집되지 않았다는 이유만으로 불일치가 되지 않는다.
   - 결과: 불일치 1,014건. 개인 코드가 있는 법인·소상공인 구분 55건도 불일치다. 소상공인은 개인사업자를 포함하지만 사업자 자격의 혜택이라 #60 전까지 불일치다. 개인 구분인데 코드가 전부 `null`인 8건은 개인 대상이며, 나머지 항목은 ‘확인 필요(코드 없음)’다.
   - 대안: 개인 코드 전부 `null`이면 비개인(보고서 근거 3/3, 불일치 967건). 실제 개인 대상 8건이 불일치로 판정된다.
   - 대안: 두 기준이 모두 비개인일 때만 불일치(959건), 엇갈리면 확인 필요. 잘못된 불일치는 가장 적지만 판정이 두 원천에 걸린다.
   - 원천이 대상을 명시한 필드이고, 보고서 근거(코드 전부 `null`)와 다르다는 점을 `DEC-MATCH` 근거에 위 집계와 함께 적는다.
2. **지원조건 코드는 조회할 때 `source_record` 원문을 파싱한다.** 2026-10-21 사용자 결정.
   - 마이그레이션이 없고 결정 0008을 그대로 따른다. 판정 근거가 항상 현재 원문이다.
   - 파싱은 `SupportConditions.parse(JsonNode)` 하나에 모은다. 저장 방식을 바꾸게 되면 판정은 그대로 두고 읽는 곳만 바꾼다.
   - 대안: 수집할 때 정규화 컬럼·테이블로 저장. #59·알림에서 SQL로 거를 수 있지만, 0008 대체, 수집기의 혜택 연결, 기존 행 채우기가 필요해 이 이슈의 범위를 넘는다. #59도 응답 시간을 측정한 뒤 개선을 별도 이슈로 두었다.
3. **판정은 순수 도메인 객체 `ConditionMatcher`가 한다.** 2026-10-21 사용자 결정.
   - 입력은 프로필 값·지원조건·사용자구분·기준일이고, 출력은 전체 결과와 항목별 판정이다. DB 없이 경계 사례를 단위 테스트로 검증하고, #59와 알림이 같은 판정을 쓴다.
   - 패키지는 `benefit`·`profile`·`collection`을 읽기만 하는 새 도메인 `kr.govbenefit.match`다. 다른 도메인은 조건 비교를 모른다.
   - 대안: 서비스 메서드 안의 분기(경계 테스트마다 DB가 필요하다), SQL 판정(이유를 만들기 어렵고 MySQL JSON 함수에 묶인다).
4. **API는 `GET /api/me/matches/{serviceId}`다.** 2026-10-21 사용자 결정(경로 이름 포함).
   - 공개 경로 밖이라 인증이 저절로 강제된다(결정 0018). #59의 목록은 `GET /api/me/matches`로 짝을 맞춘다.
   - 대안: `GET /api/benefits/{serviceId}/match`. 공개 경로 안이라 `SecurityConfig`에 예외가 필요하다.
   - 대안: 토큰이 있으면 상세 응답에 포함. 결정 0018을 대체해야 하고, 상세는 사용자를 모르는 서버 렌더링 화면이다(`DEC-APP`).
   - 계정이 없으면 401 `UNAUTHENTICATED`, 수집되지 않은 서비스 ID는 404 `NOT_FOUND`다. 프로필을 저장한 적이 없으면 모든 값이 미입력인 프로필로 판정한다.
5. **항목별 판정 규칙.** 2026-10-21 사용자 결정(a~d). b·c·d는 `DEC-MATCH`에 기록한다.
   - 항목 판정은 `MET`(충족)·`NEEDS_CHECK`(확인 필요)·`NOT_MET`(미충족, 개인 대상 항목에만)·`NOT_EVALUATED`(판정하지 않음)다.
   - 이유는 아래 순서로 처음 해당하는 것 하나다.
     1. 지원조건이 수집되지 않음 → `CONDITIONS_NOT_COLLECTED`(확인 필요)
     2. 그 항목의 코드가 없거나 해석할 수 없음 → `CODE_MISSING`·`AGE_RANGE_INVALID`(확인 필요)
     3. 프로필 미입력 → `PROFILE_MISSING`(확인 필요)
     4. 비교 결과
   - (a) 혜택에 제한이 없어도 프로필이 미입력이면 ‘확인 필요’다. 이슈 #58·#59의 완료 조건을 따른다. 그래서 위 순서에서 미입력이 제한 없음보다 앞선다.
   - 연령:
     - 시작·종료가 모두 `null`이면 `CODE_MISSING`이다. 한쪽만 있거나 시작이 종료보다 크면 `AGE_RANGE_INVALID`다.
     - (b) 시작 0은 하한 없음, 종료 120은 상한 없음이다. 둘 다면 제한 없음이고, 0·120은 경계값으로 치지 않는다.
     - 만 나이가 범위 밖이면 `CODE_EXCLUDES`, 제한이 있는 쪽의 경계값과 같으면 `AGE_ON_BOUNDARY`(둘 다 확인 필요)다. 범위 안이면 `CODE_INCLUDES`, 제한이 없으면 `NO_RESTRICTION`(둘 다 충족)이다.
     - 기준일은 요청 시점의 서울 날짜(`LocalDate.now(clock)`)다.
   - 성별: 두 코드가 모두 `null`이면 `CODE_MISSING`, 모두 `Y`이면 `NO_RESTRICTION`이다. 프로필 성별의 코드가 `Y`이면 `CODE_INCLUDES`, 아니면 `CODE_EXCLUDES`다.
   - 생애·직업·건강, 가구(그룹 안 OR):
     - 그룹 코드가 모두 `null`이면 `CODE_MISSING`이다.
     - 모두 `Y`이거나 (c) 해당사항없음 코드만 `Y`이면 `NO_RESTRICTION`이다.
     - 프로필의 코드(`NONE`이면 해당사항없음 코드) 중 하나라도 `Y`이면 `CODE_INCLUDES`, 아니면 `CODE_EXCLUDES`다.
     - 미입력(`PROFILE_MISSING`)과 `NONE`은 이렇게 다르게 판정된다.
   - 개인 대상: 설계 1. 이유는 `PERSONAL_USER_TYPE`(충족)·`NON_PERSONAL_USER_TYPE`(미충족)·`USER_TYPE_MISSING`(확인 필요)이다.
6. **전체 결과는 개인 대상 항목이 먼저 정한다.**
   - 개인 대상 항목이 미충족이면 ‘불일치’(`MISMATCH`)다. (d) 이때 나머지 항목은 판정하지 않는다(`NOT_EVALUATED`, 이유 `NOT_PERSONAL_TARGET`). 기관 대상 혜택은 개인 코드가 대부분 `null`이라 판정하면 모두 확인 필요가 되어 혼란을 준다.
   - 그 밖에 모든 항목이 충족이면 ‘부합’(`MATCH`), 하나라도 아니면 ‘확인 필요’(`NEEDS_CHECK`)다.
   - 그룹 사이 AND는 이 규칙으로 표현된다. 개인 대상 혜택은 어떤 경우에도 불일치가 되지 않는다.
7. **응답에는 이유 코드와 비교한 값을 함께 담는다.** 2026-10-21 사용자 결정(f, h).
   - 이유는 enum 코드만 주고 화면 문구는 클라이언트가 정한다(`ApplyPeriodType`과 같은 방식).
   - 항목마다 혜택 쪽 값과 프로필 쪽 값을 준다. 연령은 범위와 만 나이, 성별은 `Y`인 성별과 프로필 성별, 속성 그룹은 `Y`인 속성과 프로필 속성(결정 0022의 API 값), 개인 대상은 사용자구분 값이다.
   - 고정 표시를 enum 목록으로 준다(`notices`). 지원조건 코드 기준의 결과, 그룹 조합은 해석 규칙, 신청 가능 확정이 아님의 세 가지다.
   - (e) 비교하지 않은 조건은 고정 목록(`INCOME`, `RESIDENCE`, `OTHER_SOURCE_CONDITIONS`)이다. 소득 코드가 실제로 제한하는지를 혜택마다 보여 주는 것은 비교 항목 추가에 가까워 범위 밖이다.
   - 원문(`target`, `selectionCriteria`)과 공식 링크(`guideUrl`, `OnlineApplyUrl.toLink(benefit.getDetailUrl())`)는 결과와 관계없이 항상 담는다. 부합에도 공식 확인 경로가 필요하다.
8. **결정 문서는 새로 만들지 않는다.** 판정 규칙은 제품 동작이라 `DEC-MATCH`에 둔다. 지원조건을 원문에서 읽는 것은 결정 0008을 그대로 따른다.

## 단계별 구현 계획

1. **도메인** `kr.govbenefit.match.domain`
   - `SupportConditions`(record): `boolean male, female`, `Integer ageStart, ageEnd`, `Set<String> yesCodes`(값이 `Y`인 JA 코드).
     - `static SupportConditions parse(JsonNode)`: 문자열 `"Y"`만 `Y`로 본다. 연령은 정수 노드만 값으로, 그 밖은 `null`로 읽는다.
     - `boolean isYes(String code)`.
   - `PersonalTarget`: `static ItemJudgement judge(String userType)`. `||`로 나눠 `개인`·`가구` 포함 여부를 본다.
   - `MatchSubject`(record): `Integer age`, `Gender gender`, `List<LifeAttribute> lifeAttributes`, `List<HouseholdAttribute> householdAttributes`(그룹 미입력이면 `null`). 구현 중 변경: 코드 집합 대신 속성 enum 목록을 둔다. 응답의 프로필 값(결정 0022의 API 값)을 그대로 만들고, 비교는 `jaCode()`로 한다.
     - `static MatchSubject of(UserProfile profile, LocalDate today)`. 프로필이 `null`이면 모두 미입력이다.
   - enum:
     - `MatchResult { MATCH, NEEDS_CHECK, MISMATCH }`
     - `ItemStatus { MET, NEEDS_CHECK, NOT_MET, NOT_EVALUATED }`
     - `MatchReason`(설계 5·6의 이유 코드)
     - `MatchNotice { CODE_BASED, GROUP_RULE_INTERPRETED, NOT_FINAL }`
     - `UncomparedCondition { INCOME, RESIDENCE, OTHER_SOURCE_CONDITIONS }`
   - `ConditionMatcher`(final, static 메서드): `static MatchOutcome match(MatchSubject subject, SupportConditions conditions, String userType)`. `conditions`가 `null`이면 미수집이다.
     - 항목별 메서드: `personalTarget`, `age`, `gender`, `attributeGroup(groupCodes, noneCode, profileCodes)`.
     - 생애·가구 그룹 코드는 `LifeAttribute`·`HouseholdAttribute`의 `jaCode()`에서 얻는다(결정 0022의 대응표).
   - `MatchOutcome`(record): 전체 결과와 항목별 `ItemJudgement(status, reason)`. 비교한 값은 DTO가 입력에서 만든다.
2. **DTO** `kr.govbenefit.match.dto.MatchResponse`
   - 필드:
     - `serviceId`, `name`, `result`, `notices`, `uncomparedConditions`, `judgedOn`(기준일, `LocalDate`)
     - `items`(`MatchItems`): `personalTarget`, `age`, `gender`, `lifeAttributes`, `householdAttributes`
     - `source`(`MatchSource`): `target`, `selectionCriteria`, `guideUrl`
     - 구현 중 변경: 중첩 record 이름은 명세 스키마 이름이 되므로 `Items`·`Source` 대신 `MatchItems`·`MatchSource`로 둔다.
     - 구현 중 추가: `notices`는 불일치이면 `NOT_FINAL`만 담는다. 불일치는 지원조건 코드가 아니라 사용자구분으로 판정하고 그룹을 비교하지 않기 때문이다.
   - 항목 record마다 `status`, `reason`과 비교한 값을 둔다.
     - `AgeItem`: `ageStart`, `ageEnd`, `profileAge`
     - `GenderItem`: `allowedGenders`, `profileGender`
     - `AttributeItem`: `includedAttributes`, `profileAttributes`
     - `PersonalTargetItem`: `userType`
   - `@Schema` 설명에 "신청 가능 확정이 아니다", 각 enum 값의 뜻, `null`의 뜻(미입력·코드 없음)을 적는다.
3. **서비스** `kr.govbenefit.match.service.MatchService`, `@Transactional(readOnly = true) MatchResponse match(Long userId, String serviceId)`
   - 계정이 없으면 `BusinessException(UNAUTHENTICATED)`.
   - `benefitRepository.findByServiceId`가 없으면 `BusinessException(NOT_FOUND, "혜택이 없습니다. serviceId=...")`.
   - `userProfileRepository.findById(userId)`, `sourceRecordRepository.findByServiceIdAndEndpoint(serviceId, SUPPORT_CONDITIONS)` → `jsonMapper.readTree` → `SupportConditions.parse`.
   - `ConditionMatcher.match`로 판정하고 `MatchResponse`를 만든다. 한 읽기 트랜잭션에서 같은 스냅샷을 본다.
4. **컨트롤러** `kr.govbenefit.match.controller.MatchController`, `@RequestMapping("/api/me/matches")`, `@Tag(name = "조건 비교")`
   - `@GetMapping("/{serviceId}")` → 200. `security = BEARER_AUTH`.
   - `@ApiResponse`: 200, 401 `UNAUTHENTICATED`, 404 `NOT_FOUND`. 표기는 `BookmarkController`를 따른다.
5. **API 명세** `OpenApiConfig.DESCRIPTION`
   - 첫 줄의 API 목록에 조건 비교를 넣는다.
   - 항목을 하나 더한다: 조건 비교(`GET /api/me/matches/{serviceId}`)는 프로필과 지원조건 코드를 비교한 결과이며 신청 가능 확정이 아니다. 확인 필요이면 원문과 공식 링크를 확인한다.
6. **제품 문서**
   - `docs/product/development.md`:
     - `DEC-MATCH` 행에 설계 1(개인 대상 기준과 근거 집계), 설계 5의 b·c, 설계 6의 d를 더하고 날짜와 #58을 적는다.
     - "결정 뒤에도 남는 미확인 사항" 문단에서 개인 대상 판정 기준을 뺀다.
   - `docs/product/requirements.md`의 `REQ-MATCH-01` "남은 미확인" 중 개인 대상 판정 기준 항목을 `DEC-MATCH`로 결정했다고 고친다.
7. **테스트**
   - `match.domain.SupportConditionsTest`: `Y`·`null`·없는 키·`Y`가 아닌 문자열, 연령 정수·`null`·문자열.
   - `match.domain.PersonalTargetTest`: 실제 사용자구분 14종 조합. `개인||법인/시설/단체`는 개인 대상, `소상공인||법인/시설/단체`는 비개인, 빈 값은 확인 필요.
   - `match.domain.ConditionMatcherTest`(Spring 없음, `@ParameterizedTest` 표):
     - 모든 항목 충족 → `MATCH`, `notices`에 `CODE_BASED`.
     - 연령: 범위 안 충족, 경계값(시작·종료) 확인 필요, 범위 밖 확인 필요. 0~120 제한 없음, 시작 0인 혜택에서 만 0세는 경계가 아님, 종료 120은 경계가 아님, 한쪽 `null`·시작>종료는 `AGE_RANGE_INVALID`.
     - 성별: 둘 다 `Y`, 일치, 불일치 확인 필요, 둘 다 `null`.
     - 그룹: 하나만 `Y`여도 충족(OR), 전부 `Y`, 해당사항없음만 `Y`이고 프로필에 속성 있음 → 충족. 프로필 `NONE`이고 해당사항없음 `null` → 확인 필요. 미입력 → `PROFILE_MISSING`. 그룹 전부 `null` → `CODE_MISSING`.
     - 그룹 하나라도 미충족이면 `MATCH`가 아님.
     - 제한 없는 항목도 프로필 미입력이면 확인 필요(a).
     - 지원조건 미수집이면 개인 대상 외 항목이 모두 `CONDITIONS_NOT_COLLECTED`이고 결과가 불일치가 아님.
     - 비개인 사용자구분 → `MISMATCH`, 나머지 `NOT_EVALUATED`. 개인 대상이면 어떤 코드·프로필 조합에도 `MISMATCH`가 아님.
   - `match.controller.MatchApiTest`(`@SpringBootTest`, MockMvc, Testcontainers, `MutableClock`, `Gov24StubServer`로 실제 수집). 계정은 `SignUpSteps`, 프로필은 `PUT /api/me/profile`로 넣는다.
     - stub 레코드를 바꿔 부합·확인 필요(경계값)·불일치(사용자구분을 `법인/시설/단체`로)를 만들고, 각 응답의 `result`, 항목별 `status`·`reason`·비교한 값, `notices`, `uncomparedConditions`, `source`를 확인한다.
     - 프로필을 수정한 뒤 다시 비교하면 바뀐 값으로 판정된다.
     - 프로필을 저장한 적 없는 사용자는 200이고 개인 대상 혜택이 확인 필요다.
     - `override(SUPPORT_CONDITIONS, 1, 실패)`로 지원조건을 수집하지 못한 혜택은 확인 필요다.
     - 수집되지 않은 서비스 ID는 404다.
     - 토큰 없이 보내면 401, 탈퇴 뒤 남은 토큰은 401이다.
     - 요청에 `userId` 파라미터를 넣어도 토큰 주인의 프로필로 비교한다. A와 B의 프로필이 다르면 같은 혜택의 결과가 각자 다르다.
     - 같은 혜택의 비로그인 상세(`GET /api/benefits/{serviceId}`)는 토큰 없이 200이다.
   - `ApiDocsTest`: `/api/me/matches/{serviceId}`의 `get`이 bearer 보안을 요구하고, 응답 코드는 200·401·404·500이며, 요청 파라미터에 `userId`가 없다.
   - 기존 테스트는 바꾸지 않고 통과한다.

## 완료 조건 매핑

- [ ] 비교 항목을 코드상 모두 충족하면 ‘부합’이고, 지원조건 코드 기준의 결과라는 표시가 있다. → 설계 6·7. `ConditionMatcherTest`, `MatchApiTest`의 부합.
- [ ] 연령·성별·가구·생애 속성 중 코드상 벗어나는 항목이 있으면 ‘불일치’가 아니라 ‘확인 필요’이고, 그 항목의 이유와 원문·공식 링크가 함께 온다. → 설계 5·7. `ConditionMatcherTest`, `MatchApiTest`의 확인 필요.
- [ ] 만 나이가 연령 코드의 경계값과 같으면 ‘확인 필요’이고, 경계 안이면 그 항목은 충족, 경계 밖이면 ‘확인 필요’다. → 설계 5(b). `ConditionMatcherTest`의 연령.
- [ ] 개인 대상이 아닌 혜택만 ‘불일치’이고, 개인 대상 혜택은 어떤 경우에도 ‘불일치’가 아니다. → 설계 1·6. `PersonalTargetTest`, `ConditionMatcherTest`, `MatchApiTest`의 불일치.
- [ ] 프로필 항목이 미입력이거나 지원조건 코드가 없거나 지원조건이 수집되지 않았으면 그 항목은 ‘확인 필요’이고, 이것만으로 ‘불일치’가 되지 않는다. → 설계 1·5. `ConditionMatcherTest`, `MatchApiTest`의 미수집·프로필 없음.
- [ ] 가구·생애 속성에서 ‘해당 없음’과 미입력이 서로 다르게 판정된다. → 설계 5. `ConditionMatcherTest`의 그룹.
- [ ] 그룹 안에 하나라도 해당하면 그 그룹은 충족이고, 그룹 하나라도 미충족이면 전체가 ‘부합’이 아니다. 해석 규칙이라는 표시가 결과에 있다. → 설계 5·6·7. `ConditionMatcherTest`, `MatchApiTest`의 `notices`.
- [ ] 비교하지 않은 조건과 공식 확인 경로가 결과에 있고, 어떤 결과도 ‘신청 가능 확정’으로 표현되지 않는다. → 설계 7. `MatchApiTest`, API 명세.
- [ ] 프로필을 수정한 뒤 다시 비교하면 바뀐 값으로 판정된다. → 설계 2·3(조회할 때 계산). `MatchApiTest`.
- [ ] 다른 사용자의 프로필로 비교할 수 없고, 인증되지 않은 비교 요청은 거절된다. 수집되지 않은 서비스 ID는 찾을 수 없음으로 응답한다. → 설계 4, 결정 0018. `MatchApiTest`, `ApiDocsTest`.
- [ ] 개인 대상 여부의 판정 기준이 `DEC-MATCH`에 기록되어 있다. → 설계 1, 단계 6.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인: `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤 가입·로그인으로 액세스 토큰을 받고 프로필을 저장한다. 그다음 개인 대상 혜택(`$SID`)과 `법인/시설/단체` 혜택(`$CORP`)으로 다음을 실행한다.
  ```sh
  curl -s localhost:8080/api/me/matches/$SID -H "Authorization: Bearer $TOKEN"
  curl -s localhost:8080/api/me/matches/$CORP -H "Authorization: Bearer $TOKEN"
  curl -si localhost:8080/api/me/matches/$SID
  curl -si localhost:8080/api/me/matches/NO_SUCH_ID -H "Authorization: Bearer $TOKEN"
  ```
  다음을 확인한다.
  - 개인 대상 혜택은 `MATCH` 또는 `NEEDS_CHECK`이고 항목별 값이 있다.
  - 법인 혜택은 `MISMATCH`이고 나머지 항목이 `NOT_EVALUATED`다.
  - 토큰이 없으면 401, 없는 ID는 404다.

## 결정 필요

없음
