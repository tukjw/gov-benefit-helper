status: in-progress

# 구현 계획 — #68 조건 비교에 운영 기관 지역 판정과 내 지역·전체 지역 보기 추가

브랜치: `feat/68-match-agency-region` (develop `4e2d648` 기준)

## 목표 및 범위

- 목표: 조건 비교에 ‘운영 기관 지역’ 항목을 넣어, 다른 지역 기관의 혜택을 ‘확인 필요’로 판정하고 조건 비교 목록을 기본으로 내 지역 기관과 지역 없는 기관의 혜택으로 좁힌다. 혜택 검색에는 운영 기관 지역(시도·시군구) 필터를 더한다. 거주 요건이 아니라 운영 기관 소재지 기준임을 결과와 문서에 밝힌다.
- 포함:
  - 상세 비교(`GET /api/me/matches/{serviceId}`)와 목록(`GET /api/me/matches`)의 `agencyRegion` 항목. 상세에는 기관 지역과 프로필 지역을 함께 담는다.
  - 목록의 지역 범위 파라미터 `regionScope`(`MY_REGION`·`ALL`)와 실제로 적용한 범위를 담은 응답.
  - 검색(`GET /api/benefits`)의 `agencySidoCode`·`agencySigunguCode` 필터.
  - 공개 지역 목록 API(`GET /api/benefits/regions`). 2026-10-25 사용자 결정으로 이번 이슈에 넣었다(이슈 작업 내용에는 없음). 지역 코드가 행정표준기관코드라 목록이 없으면 로그인하지 않은 클라이언트가 검색 필터 값을 만들 수 없다.
  - 교육청 코드를 시도에 잇는 마이그레이션, 결정 문서 0025, 제품 문서 갱신, API 명세.
- 제외(이슈 본문):
  - 원문 문구로 거주 요건 판정, 행정구역 개편 처리.
  - 웹 화면(검색 화면에서 프로필 거주지를 기본값으로 채우는 것 포함).
  - 키워드·분야·기관 유형 필터와 조건 비교 목록의 결합(#61).
  - 조건 비교 목록을 DB에서 미리 좁히는 성능 개선(설계 4).

## 확인된 사실과 제약

- 로컬 수집 데이터(2026-10-01, 10,950건)의 기관 유형별 `agency_code`:

  | 소관기관유형 | 건수 | 서로 다른 코드 | `region`에 있는 코드 |
  | --- | --- | --- | --- |
  | 시군구 | 6,505 | 227 | 전부 |
  | 광역시도 | 1,389 | 16 | 전부 |
  | 지방출자_출연기관 | 580 | 85 | 전부 |
  | 지방공기업 | 561 | 79 | 전부 |
  | 교육청 | 261 | 16 | 없음 |
  | 중앙행정기관 | 1,054 | 33 | 없음 |
  | 공공기관 | 600 | 96 | 없음 |

  유형이나 코드가 빈 행은 없다.
- 교육청 16곳은 시도마다 하나이고 코드가 시도 코드와 다르다(`7010000` 서울특별시교육청, `7140000` 전남광주통합특별시교육청, `7150000` 부산, `7240000` 대구, `7310000` 인천, `7430000` 대전, `7480000` 울산, `7530000` 경기도, `7801000` 강원특별자치도, `8000000` 충청북도, `8140000` 충청남도, `8321000` 전북특별자치도, `8750000` 경상북도, `9010000` 경상남도, `9290000` 제주특별자치도, `9300000` 세종특별자치시).
- 결정 0021: `region`은 행정표준기관코드로 시도 16개·시군구 227개를 마이그레이션으로 고정한다. 시군구 코드는 시도 코드를 포함하지 않아 소속은 `parent_code`로만 안다. 운영 기관 지역 필터는 `region`과 `benefit.agency_code`로 정하며 기관명을 해석하지 않는다. 지역 목록이 공개 API로 필요해지면 별도 경로로 나누고 0018의 공개 경로 기준을 따른다.
- 결정 0018: 공개 경로는 `/api/benefits/**`, `/api/auth/**` 등이다. 채택된 결정 문서의 본문은 고치지 않는다(`docs/decisions/README.md`).
- `ConditionMatcher`는 DB를 모르는 순수 함수다. 항목 이유는 데이터 문제 → 프로필 미입력 → 비교 결과 순으로 정한다. 혜택에 제한이 없어도 프로필이 미입력이면 확인 필요다(`DEC-MATCH`).
- `MatchService.list`는 요청마다 전체 혜택을 `SEARCH_ORDER`로 읽어 판정하고, 결과별로 안정 분할한 뒤 메모리에서 페이지를 자른다(#59).
- `ProfileValidator`의 시도·시군구 검증은 `regionRepository.findById`로 하고, 오류는 `InvalidInputException`의 항목별 오류(`sidoCode`·`sigunguCode`)로 모은다. 문구: "지원하지 않는 시도 코드입니다.", "시도와 함께 입력해야 합니다.", "지원하지 않는 시군구 코드입니다.", "선택한 시도의 시군구가 아닙니다."
- 검색의 키워드 단어 수 오류는 `BenefitSearchCondition.of`가 `InvalidInputException`으로 던진다. 범위 오류는 Bean Validation이다.
- 지역 선택지는 지금 `GET /api/me/profile/options`(로그인 필요)의 `sidos`로만 나온다. `RegionResponse`와 `ProfileOptionsResponse.SidoOption`은 `profile.dto`에 있다.
- `UncomparedCondition.RESIDENCE`(거주 지역)는 비교하지 않은 조건으로 고정 노출된다. 거주 요건은 이번에도 비교하지 않는다.
- 테스트 stub fixture의 세 서비스는 모두 소관기관유형 `시군구`다. #59처럼 `Gov24StubServer.records(endpoint)`에 레코드를 복사해 기관 유형·코드를 바꾼 서비스를 더할 수 있다.

## 설계 결정

2026-10-25 사용자 결정. 대안과 장단점은 같은 날 대화에서 비교했다.

1. **교육청 코드를 `region` 시도 행에 컬럼으로 두고, 기관 지역은 조회할 때 계산한다.** → [결정 0025](../decisions/0025-agency-region.md)
   - `region.education_office_code CHAR(7) NULL UNIQUE`를 더하고 시도 16행에 값을 넣는다. 지역 기준이 `region` 한 곳에 모이고 시드 테스트로 검증된다.
   - 요청마다 `region` 243행을 한 번 읽어 `Regions`(코드 → 지역 조회)를 만들고, 비교와 검색이 같은 조회를 쓴다.
   - 대안: 교육청 매핑을 Java 상수로 둠(지역 기준이 둘로 나뉨, 0021이 정적 enum을 기각한 이유와 같음), 수집 때 `benefit`에 기관 시도·시군구를 저장(수집·백필·재계산 책임이 생기고 지금 규모에서 이득이 없음), 기관명 접두어 매칭(0021과 충돌).
2. **지역 없는 기관은 기관 유형으로 정한다.** → 결정 0025
   - `중앙행정기관`·`공공기관`은 지역 없음이다.
   - `광역시도`·`시군구`·`지방공기업`·`지방출자_출연기관`은 `agency_code`를 `region`에서 찾는다. `교육청`은 `education_office_code`에서 찾아 그 시도로 본다.
   - 지자체 유형인데 코드를 찾지 못했거나, 처음 보는 유형이면 ‘지역 모름’이다. ‘전국’으로 채우지 않는다(`REQ-SEARCH-01`).
   - 대안: 코드 기준(`region`·교육청에 없으면 지역 없음). 매핑하지 못한 지자체 혜택이 조용히 충족이 된다. 지금 데이터에서는 두 기준의 결과가 같다.
3. **검색 지역 필터는 기관 코드 IN 목록으로 만든다.** → 결정 0025
   - 시도 S: `(agency_type IN 지자체 4유형 AND agency_code IN (S, S의 시군구들)) OR (agency_type = '교육청' AND agency_code = S의 교육청 코드)`.
   - 시군구 G: `agency_type IN 지자체 4유형 AND agency_code = G`.
   - 유형 조건을 함께 걸어 설계 2의 분류와 검색 결과가 어긋나지 않게 한다(중앙·공공기관 코드가 지역 코드와 같아져도 나오지 않는다).
   - 대안: `region` 조인·서브쿼리 Specification(엔티티 연관이 없어 Criteria가 복잡함), 정규화 컬럼 비교(설계 1의 대안을 전제로 함).
4. **목록의 지역 범위는 판정한 뒤 메모리에서 거른다.**
   - 범위 판단은 판정과 같은 `Regions`·`AgencyRegion`을 쓰는 도메인 함수 하나(`AgencyRegionScope.includes`)로 둔다. 범위 규칙이 SQL과 Java 두 곳에 생기지 않는다.
   - 구현 중 변경: 클래스를 따로 두지 않고 `AgencyRegion.isWithin(sidoCode, sigunguCode)`로 두었다. 범위가 기관 지역의 성질이라 같은 record에 있는 편이 읽기 쉽다.
   - 범위는 판정 결과가 아니라 "기관 지역이 내 거주지 범위 안인가"로 정한다. 시도만 입력한 사용자의 도내 시군구 기관은 판정이 확인 필요지만 기본 범위에 들어가기 때문이다(설계 6).
   - 대안: DB에서 먼저 좁힘. 판정 건수가 13~20%로 줄지만 범위 규칙이 두 벌이 된다. 성능은 #59 이후 측정 이슈에서 다룬다.
5. **운영 기관 지역 판정과 이유 코드**

   | 기관 지역 \ 프로필 | 미입력 | 시도만 | 시도·시군구 |
   | --- | --- | --- | --- |
   | 지역 없음(중앙·공공) | 충족 `NO_RESTRICTION` | 충족 `NO_RESTRICTION` | 충족 `NO_RESTRICTION` |
   | 지역 모름 | 확인 필요 `AGENCY_REGION_UNKNOWN` | 같음 | 같음 |
   | 시도 기관(광역시도, 시도 소속 공기업·출연기관, 교육청) | 확인 필요 `PROFILE_MISSING` | 같은 시도면 충족 `REGION_INCLUDES`, 아니면 확인 필요 `REGION_EXCLUDES` | 같음 |
   | 시군구 기관(시군구, 시군구 소속 공기업·출연기관) | 확인 필요 `PROFILE_MISSING` | 다른 시도면 `REGION_EXCLUDES`, 같은 시도면 비교할 수 없어 `PROFILE_MISSING` | 같은 시군구면 `REGION_INCLUDES`, 아니면 `REGION_EXCLUDES` |

   - 지역 없는 기관은 거주지 미입력이어도 충족이다(2026-10-25 사용자 결정). 기관에 지역이 없다는 것은 프로필 값과 무관한 사실이라 비교할 값이 필요 없다. `DEC-MATCH`의 "제한이 없어도 프로필이 미입력이면 확인 필요" 규칙의 예외이며 문서에 밝힌다. 대안인 확인 필요는 거주지를 비운 사용자의 부합이 0건이 된다.
   - 같은 시도·도내 시군구·같은 시군구를 이유로 나누지 않는다. 상세 응답에 기관 지역과 프로필 지역 값이 함께 있어 값으로 구분된다.
   - 새 이유는 `REGION_INCLUDES`, `REGION_EXCLUDES`, `AGENCY_REGION_UNKNOWN` 셋이다. `CODE_INCLUDES`·`CODE_EXCLUDES`는 지원조건 코드 기준이라는 뜻이 있어 재사용하지 않는다.
   - 이유 우선순위: 기관 지역 모름 → 프로필 미입력 → 비교 결과. 지원조건 수집 여부와는 무관하다(`CONDITIONS_NOT_COLLECTED`를 쓰지 않는다).
   - 개인 대상이 아니면 다른 항목처럼 `NOT_EVALUATED`다.
   - 다른 항목이 모두 충족이어도 지역이 확인 필요면 결과는 확인 필요다. 불일치는 여전히 개인 대상이 아닌 혜택에만 쓴다.
6. **목록의 지역 범위**
   - `MY_REGION`은 지역 없는 기관과, 기관 지역이 거주지 범위 안인 기관이다.
     - 시도·시군구를 입력했으면: 내 시도의 시도 기관(교육청 포함), 내 시군구 기관.
     - 시도만 입력했으면: 내 시도의 시도 기관과 도내 모든 시군구 기관(2026-10-25 사용자 결정).
     - 지역 모름은 넣지 않는다(2026-10-25 사용자 결정). `ALL`에서만 나온다. 검색 지역 필터에서도 나오지 않으므로 동작이 같다. 개편 뒤 마이그레이션 전까지 해당 지역 혜택이 기본 범위에서 빠지는 것은 한계로 문서에 남긴다.
   - `ALL`은 개인 대상 혜택 전체다.
   - 파라미터를 생략하면 거주지가 있을 때 `MY_REGION`, 없을 때 `ALL`이다. 거주지 없이 `MY_REGION`을 명시해도 `ALL`로 처리한다. 응답의 `regionScope`에 실제로 적용한 범위를 담는다(2026-10-25 사용자 결정).
   - 범위가 달라도 정렬·페이지 처리는 같고 `totalCount`는 범위 안 건수다. 불일치는 범위와 관계없이 빠진다.
7. **검색 지역 필터 파라미터**
   - 이름은 `agencySidoCode`·`agencySigunguCode`이며 값은 하나씩이다(2026-10-25 사용자 결정: 값 하나). 기존 `agencyType`과 맞추고 거주지가 아니라 운영 기관 지역임을 이름에 드러낸다(`REQ-SEARCH-01`).
   - 검증은 프로필과 같은 규칙·문구다. 시군구만 보내면 "시도와 함께 입력해야 합니다."로 거절한다. 항목별 오류의 `field`는 이 파라미터 이름이다.
   - 시군구를 보내면 그 시군구 기관만 나오고 시도 기관은 나오지 않는다(이슈 완료 조건).
   - 키워드 단어 수 오류가 있으면 그 오류가 먼저 나간다. 지역 오류와 한 응답으로 모으지 않는다.
8. **지역 검증과 지역 목록을 `region` 패키지로 옮긴다.**
   - 검색과 프로필이 같은 기준을 쓰도록 `ProfileValidator.sido()`·`sigungu()`를 `region.service.RegionCodeValidator`로 옮긴다. 필드 이름은 호출하는 쪽이 넘긴다. 프로필의 오류 응답은 바뀌지 않는다.
   - 공개 지역 목록과 프로필 선택지가 같은 목록을 쓰도록 `ProfileService.options()`의 지역 트리 구성을 `region.service.RegionService.sidos()`로 옮긴다. `RegionResponse`·`SidoOption`을 `region.dto`로 옮기되 이름은 그대로 두어 API 명세의 스키마 이름이 바뀌지 않는다.
9. **공개 지역 목록은 `GET /api/benefits/regions`다.**
   - `/api/benefits/**`는 이미 공개 경로라 0018을 대체하지 않는다. 지역 목록은 혜택 검색의 운영 기관 지역 필터 선택지다.
   - `/api/benefits/{serviceId}`와 경로가 겹치지만 Spring MVC는 고정 경로를 먼저 고르고, 서비스 ID는 숫자 문자열이라 `regions`인 혜택은 없다. 테스트로 고정한다.
   - 대안: `/api/regions`. 자원 경로로는 더 자연스럽지만 0018의 공개 경로 목록이 바뀌어 0018을 대체하는 결정 문서가 필요하다.
   - 응답은 `{ sidos: [{ code, name, sigungus: [{ code, name }] }] }`로 프로필 선택지의 `sidos`와 같다. 교육청은 따로 내지 않는다(시도 필터에 포함된다).
10. **결과 표시**
    - `MatchNotice.AGENCY_REGION_BASED`(지역 항목은 거주 요건이 아니라 운영 기관 소재지 기준이다)를 더한다. 상세(불일치 제외)와 목록의 `notices`에 들어간다.
    - `UncomparedCondition.RESIDENCE`는 남기고 설명을 "원문의 거주 요건. 운영 기관 지역만 비교한다"로 바꾼다.

## 단계별 구현 계획

1. **마이그레이션** `V11__add_region_education_office.sql`
   - `ALTER TABLE region ADD COLUMN education_office_code CHAR(7) NULL AFTER parent_code, ADD CONSTRAINT uk_region_education_office UNIQUE (education_office_code)`.
   - 시도 16행 `UPDATE`(확인된 사실의 코드). 주석에 결정 0025와 확인 날짜(2026-10-01 수집분)를 적는다.
2. **지역 도메인** `kr.govbenefit.region`
   - `Region`: `educationOfficeCode` 필드(시도만 값이 있다).
   - `domain.AgencyCategory` enum: `NO_REGION`(중앙행정기관, 공공기관), `LOCAL_GOVERNMENT`(광역시도, 시군구, 지방공기업, 지방출자_출연기관), `EDUCATION_OFFICE`(교육청), `UNKNOWN`. `static AgencyCategory of(String agencyType)`, `List<String> agencyTypes()`.
   - `domain.AgencyRegion` record(`Kind kind`, `Region sido`, `Region sigungu`). `Kind`: `NONE`, `LOCATED`, `UNKNOWN`. 시도 기관이면 `sigungu`가 null이다.
     - 구현 중 변경: `Region` 대신 코드(`sidoCode`, `sigunguCode`)를 담는다. 판정은 코드 비교만 하고, 이름은 응답을 만들 때 `Regions.find`로 찾는다. `match.domain` 단위 테스트에서 엔티티 없이 값을 만들 수 있다.
   - `domain.Regions`: `static Regions of(List<Region> all)`. `Optional<Region> find(String code)`, `AgencyRegion agencyRegionOf(String agencyType, String agencyCode)`, `AgencyFilter agencyFilterOf(Region sido, Region sigungu)`(검색용: 지자체 코드 목록과 교육청 코드 목록).
   - `AgencyRegion.isWithin(String sidoCode, String sigunguCode)`(설계 6, 구현 중 변경).
   - `service.RegionCodeValidator` @Component: `RegionSelection validate(String sidoField, String sidoCode, String sigunguField, String sigunguCode, List<InputError> errors)`. 본문은 `ProfileValidator`의 두 메서드를 옮긴 것이다.
   - `service.RegionService`: `List<SidoOption> sidos()`, `Regions regions()`.
   - `dto.RegionResponse`, `dto.SidoOption`(`profile.dto`에서 옮김).
3. **프로필**
   - `ProfileValidator`: `RegionCodeValidator`에 위임한다(필드 `sidoCode`·`sigunguCode`).
   - `ProfileService.options()`: `RegionService.sidos()`를 쓴다. `ProfileOptionsResponse`는 `region.dto.SidoOption`을 담는다.
4. **검색** `kr.govbenefit.benefit`
   - `BenefitSearchRequest`: `agencySidoCode`, `agencySigunguCode`(`@Parameter` 설명: 운영 기관 소재 시도·시군구이며 거주 요건이 아님, 지역 목록 API에서 고름, 시도를 고르면 도내 시군구 기관과 교육청 포함, 중앙·공공기관은 나오지 않음).
   - `BenefitSearchCondition`: 두 코드 필드를 더한다.
   - `BenefitSpecifications.agencyRegionIn(AgencyFilter filter)`(설계 3). 필터가 없으면 `unrestricted()`.
   - `BenefitQueryService.search`: 지역 코드가 있으면 `RegionCodeValidator`로 검증해 오류가 있으면 `InvalidInputException`, 없으면 `Regions.agencyFilterOf`로 Specification을 더한다.
   - 컨트롤러에서 `RegionService.sidos()` → `region.dto.RegionListResponse(List<SidoOption> sidos)`.
   - 구현 중 확인: 빈 지역 코드(`agencySidoCode=`)는 다른 필터처럼 조건이 없는 것으로 본다(`BenefitSearchCondition`).
   - `BenefitController`: `GET /api/benefits/regions`(200). 검색 설명에 지역 필터를 더한다.
5. **조건 비교** `kr.govbenefit.match`
   - `MatchSubject`: `sidoCode`, `sigunguCode`를 더한다.
   - `MatchReason`: `REGION_INCLUDES`, `REGION_EXCLUDES`, `AGENCY_REGION_UNKNOWN`. 클래스 주석의 우선순위 설명을 고친다.
   - `ConditionMatcher.match(MatchSubject, SupportConditions, String userType, AgencyRegion agencyRegion)`, `static ItemJudgement agencyRegion(MatchSubject, AgencyRegion)`(설계 5).
   - `MatchOutcome`: `agencyRegion` 항목.
   - `MatchNotice.AGENCY_REGION_BASED`, `UncomparedCondition.RESIDENCE` 주석.
   - `dto.RegionScope` enum(`MY_REGION`, `ALL`). `MatchListRequest`에 `regionScope`(없으면 null).
   - `MatchService.match`: `Regions`로 기관 지역을 구하고 `MatchResponse.of`에 기관 지역·프로필 지역을 넘긴다.
   - `MatchService.list(Long userId, RegionScope requested, int page, int size)`: 적용 범위를 정하고(설계 6), 판정 뒤 `MY_REGION`이면 `AgencyRegionScope.includes`로 거른다. 정렬·분할·페이지는 그대로다.
   - `MatchResponse.MatchItems.agencyRegion`: `AgencyRegionItem(status, reason, AgencyRegionValue agency, ResidenceValue profile)`. `agency`는 지역 없음·모름이면 null이고 `{ sido: RegionResponse, sigungu: RegionResponse|null }`, `profile`은 `{ sido, sigungu }`(미입력은 null). 설명에 운영 기관 지역 기준이며 거주 요건이 아님을 적는다.
     - 구현 중 변경: 두 값의 타입을 `RegionValue` 하나로 두고 필드 이름은 `agencyRegion`·`profileRegion`으로 했다. 지역 없음과 지역 모름을 구분할 수 있게 소관기관유형 원문(`agencyType`)을 함께 담는다.
   - `MatchListResponse`: `regionScope`(적용한 범위), `MatchItemResults.agencyRegion`.
   - `MatchController`: 목록 설명에 범위 규칙을 더한다.
6. **API 명세** `OpenApiConfig.DESCRIPTION`: 조건 비교의 운영 기관 지역 항목과 범위, 검색 지역 필터, 지역 목록 API.
7. **결정 문서**
   - `docs/decisions/0025-agency-region.md`(템플릿): 설계 1·2·3과 교육청 매핑의 근거 데이터, 한계(개편, 지역 모름).
   - `docs/decisions/README.md` 목록 표에 0025를 더한다. 0021·0018은 대체하지 않는다.
8. **제품 문서** 변경일 2026-10-25와 이유(이슈 배경의 데이터 확인 결과와 한계)를 남긴다.
   - `development.md`: `DEC-SCOPE`(운영 기관 지역을 조건 비교·목록 범위에 쓴다, 거주 요건으로는 여전히 쓰지 않는다), `DEC-PROFILE`(거주 시도·시군구를 조건 비교와 목록 범위에 쓴다), `DEC-MATCH`(비교 항목과 부합 정의에 운영 기관 지역, 설계 5·6 규칙), 112행의 "운영 기관 지역 … 필터는 P1" 문장.
   - `requirements.md`: `REQ-MATCH-01`(비교 항목, 범위, 남은 미확인에 한계), `REQ-PROFILE-01`(거주 지역의 용도), `REQ-SEARCH-01`(운영 기관 지역 필터를 P0으로, 지역 목록 API).
   - 한계: 중앙 혜택의 특수 지역 요건(농촌, 보호지역, 수계 등)은 판정하지 못한다. 비거주자에게 열린 지자체 혜택도 다른 지역 거주자에게는 확인 필요다. 개편 뒤 마이그레이션 전까지 새 기관 코드는 지역 모름이다.
9. **테스트**
   - `region.domain.RegionsTest`(단위): 유형별 분류, 교육청 → 시도, 지자체 유형인데 없는 코드·처음 보는 유형 → 지역 모름, 시도·시군구 필터 코드 목록.
   - 구현 중 확인: 마이그레이션의 교육청 매핑 16건을 로컬 수집 데이터의 교육청 이름(`<시도명>교육청`)과 SQL로 대조해 모두 맞았다.
   - 설계 6의 범위 규칙은 `RegionsTest`에서 `AgencyRegion.isWithin`으로 검증한다(구현 중 변경).
   - `match.domain.ConditionMatcherTest`: 설계 5 표의 모든 칸, 이유 우선순위, 지역만 확인 필요여도 결과가 확인 필요, 개인 대상이 아니면 `NOT_EVALUATED`.
   - `RegionSeedTest`: 시도 16행 모두 교육청 코드가 있고, 시군구는 없고, 중복이 없고, 지역 코드와 겹치지 않는다.
   - `MatchApiTest`: 상세의 `agencyRegion`(판정·이유·기관 지역·프로필 지역), 중앙·공공, 같은 시도, 도내 시군구, 같은 시군구, 다른 시군구, 다른 시도, 교육청, 거주지 미입력, 시도만 입력. 프로필 거주지를 바꾸면 다음 비교에 반영. `notices`에 `AGENCY_REGION_BASED`.
   - `MatchListApiTest`: 기본 범위(시도·시군구 입력, 시도만 입력, 거주지 없음), `ALL`이면 개인 대상 전체, 거주지 없이 `MY_REGION`을 명시하면 `ALL`이 적용되고 응답에 `ALL`, 지역 모름은 `MY_REGION`에 없고 `ALL`에 있음, 범위를 바꿔도 순서·페이지가 같고 `totalCount`가 범위를 따름, 개인 대상이 아닌 혜택은 어느 범위에도 없음, 다른 지역 혜택이 `ALL`에서 확인 필요로 나옴, 항목의 `agencyRegion`이 상세와 같음, 잘못된 `regionScope`는 400.
   - `BenefitSearchApiTest`: 시도 필터(시도·도내 시군구·교육청·도내 공기업만, 중앙·공공 없음), 시군구 필터(그 시군구 기관만), 다른 필터와 AND, 필터 없으면 기존과 같음, 지원하지 않는 시도·시군구 코드, 시도에 속하지 않는 시군구, 시도 없는 시군구가 프로필과 같은 문구의 항목별 오류.
   - 지역 목록: `GET /api/benefits/regions`가 로그인 없이 200이고 프로필 선택지의 `sidos`와 같다. `GET /api/benefits/{serviceId}`는 그대로 동작한다.
   - `ProfileApiTest`: 지역 오류 응답이 바뀌지 않는다(기존 테스트 통과로 확인). 프로필 선택지의 `sidos`가 공개 지역 목록과 같다.
   - `ApiDocsTest`: 검색 파라미터에 `agencySidoCode`·`agencySigunguCode`, 목록 파라미터에 `regionScope`, `/api/benefits/regions`는 보안 요구 없음.

## 완료 조건 매핑

- [ ] 상세 비교에 운영 기관 지역 항목이 있고 판정·이유·기관 지역·프로필 지역이 반환된다. → 설계 5·10. `MatchApiTest`.
- [ ] 중앙·공공기관, 같은 시도, 도내 시군구, 같은 시군구, 다른 시군구, 다른 시도, 교육청, 거주지 미입력, 시도만 입력인 경우마다 정해진 결과가 나온다. → 설계 2·5. `ConditionMatcherTest`, `MatchApiTest`.
- [ ] 다른 지역 혜택은 불일치가 아니라 확인 필요이며, 전체 지역을 선택하면 목록에 나온다. → 설계 5·6. `MatchListApiTest`.
- [ ] 다른 항목을 모두 충족해도 지역이 확인 필요면 혜택 결과는 확인 필요다. → 설계 5. `ConditionMatcherTest`.
- [ ] 조건 비교 목록의 기본 범위는 내 지역 기관과 지역 없는 기관이고, 전체 지역을 고르면 모든 개인 대상 혜택이 나온다. 거주지가 없으면 기본이 전체 지역이다. → 설계 4·6. `MatchListApiTest`, `AgencyRegionScopeTest`.
- [ ] 지역 범위를 바꿔도 정렬(부합 → 확인 필요, 이름·서비스 ID 순)과 페이지 처리는 기존과 같고, 결과 건수는 선택한 범위를 따른다. → 설계 4·6. `MatchListApiTest`.
- [ ] 개인 대상이 아닌 혜택은 지역 범위와 관계없이 조건 비교 목록에 나오지 않는다. → 설계 6. `MatchListApiTest`.
- [ ] 프로필의 거주지를 바꾸면 다음 비교에 바로 반영된다. → 단계 5(요청마다 프로필을 읽음). `MatchApiTest`, `MatchListApiTest`.
- [ ] 검색에서 시도를 고르면 그 시도 기관, 도내 시군구 기관, 교육청 혜택만 나오고 중앙·공공기관 혜택은 나오지 않는다. → 설계 3. `BenefitSearchApiTest`, `RegionsTest`.
- [ ] 검색에서 시군구를 고르면 그 시군구 기관 혜택만 나온다. → 설계 3·7. `BenefitSearchApiTest`.
- [ ] 지원하지 않는 지역 코드, 시도에 속하지 않는 시군구 코드는 프로필 저장과 같은 기준의 항목별 오류로 거절된다. → 설계 7·8. `BenefitSearchApiTest`, `ProfileApiTest`.
- [ ] 검색 지역 필터는 다른 필터와 AND로 적용되고, 필터가 없을 때의 검색 결과는 기존과 같다. → 설계 3. `BenefitSearchApiTest`.
- [ ] 결과와 문서에 '거주 요건이 아니라 운영 기관 지역 기준'이라는 표시와 한계가 반영되어 있다. → 설계 10, 단계 6·7·8.
- [ ] 제품 문서(`DEC-SCOPE`, `DEC-PROFILE`, `DEC-MATCH`, `REQ-MATCH-01`, `REQ-PROFILE-01`, `REQ-SEARCH-01`)에 변경일과 이유가 남아 있다. → 단계 8.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인: `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤 가입·로그인으로 액세스 토큰을 받고, 프로필에 경기도(`6410000`) 수원시를 저장한다.
  ```sh
  curl -s 'localhost:8080/api/benefits/regions'
  curl -s 'localhost:8080/api/benefits?agencySidoCode=6410000&size=1'
  curl -si 'localhost:8080/api/benefits?agencySigunguCode=3000000'
  curl -s 'localhost:8080/api/me/matches?size=1' -H "Authorization: Bearer $TOKEN"
  curl -s 'localhost:8080/api/me/matches?regionScope=ALL&size=1' -H "Authorization: Bearer $TOKEN"
  ```
  다음을 확인한다.
  - 지역 목록이 로그인 없이 나온다.
  - 경기도 필터의 `totalCount`에 중앙·공공기관이 없고, 시도 없는 시군구 필터는 400이다.
  - 기본 범위의 `regionScope`가 `MY_REGION`이고 `totalCount`가 `ALL`보다 작다. `ALL`의 `totalCount`는 지금과 같다(약 9,900건).
- 구현 중 확인(2026-10-25, 로컬 수집 데이터 10,950건):
  - 지역 목록은 시도 16개·시군구 227개다.
  - 경기도 필터 1,377건, 경기도 수원시 필터 48건으로 같은 조건의 SQL 집계와 같다. 경기도 필터에 중앙행정기관·공공기관은 0건, 교육청은 16건이다.
  - 수원시 거주자의 기본 범위는 1,309건으로 이슈 배경의 ‘지역 항목 충족 1,309건’과 같고, 전체 지역은 9,936건(개인 대상 전체)이다. 경기도만 입력하면 2,350건, 거주지가 없으면 `MY_REGION`을 보내도 `ALL` 9,936건이다.
  - 상세 비교에서 부산 수영구 기관은 `REGION_EXCLUDES`, 중앙행정기관은 `NO_RESTRICTION`, 경기도교육청은 `REGION_INCLUDES`, 성남시 기관은 `REGION_EXCLUDES`이고 기관 지역·프로필 지역 이름이 함께 나온다.

## 결정 필요

없음
