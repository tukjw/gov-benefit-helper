status: done

# 구현 계획 — #59 조건 비교 결과 순으로 혜택 목록 제공

브랜치: `feat/59-matched-benefit-list` (develop `f2106bc` 기준)

## 목표 및 범위

- 목표: 로그인한 사용자가 자기 프로필로 비교한 결과 순서(‘부합’ → ‘확인 필요’, 같은 결과 안에서는 이름·서비스 ID 순)대로 혜택 목록을 페이지 단위로 받는다(`REQ-MATCH-01`, `DEC-MATCH`).
- 포함:
  - `GET /api/me/matches`(200). #58의 `GET /api/me/matches/{serviceId}`와 짝을 이룬다.
  - 항목마다 전체 결과와 비교 항목별 판정·이유. 판정은 #58의 `ConditionMatcher`를 그대로 쓴다.
  - 개인 대상이 아닌 혜택(‘불일치’)은 목록에서 뺀다.
  - API 명세, `ApiDocsTest`.
- 제외(이슈 본문):
  - 검색 키워드·분야·기관 유형 필터와의 결합(#61).
  - 비교 항목 추가, 소상공인·창업 단계 비교(#60), 알림 대상 선정(3차).
  - 응답 시간 측정과 그에 따른 성능 개선. 2026-10-23 사용자 결정으로 이슈에서 뺐다. #59를 머지한 뒤 이 구현을 기준선으로 측정·개선하는 별도 이슈를 만든다.
  - 항목별 비교한 값(연령 범위, 프로필 값 등)과 원문·공식 링크. 상세 비교 API에서 본다(설계 3).

## 확인된 사실과 제약

- 정렬 기준인 결과가 사용자 프로필과 혜택 지원조건의 조합으로 정해져서 DB의 `ORDER BY … LIMIT`으로 자를 수 없다. 판정은 Java(`ConditionMatcher.match(subject, conditions, userType)`)에 있고, 지원조건은 `source_record.payload`(MySQL `JSON`)에 원문으로만 있다(결정 0008, #58 설계 2).
- 로컬 수집 데이터(2026-10-21) 규모: 혜택 10,950건, 지원조건 원문 10,950건, 평균 837바이트(합계 약 8.7MB). 지원조건이 없는 혜택은 0건이지만 수집 실패로 생길 수 있다.
- `source_record`와 `benefit` 사이에는 FK·JPA 연관이 없다. `service_id`로만 이어진다.
- `SupportConditions.parse`는 `path()`로 읽으므로 키가 없거나 값의 형식이 달라도 예외 없이 `null`·미포함으로 읽는다. 컬럼이 `JSON` 타입이라 깨진 JSON은 저장되지 않는다.
- 검색 정렬은 `BenefitQueryService.SEARCH_ORDER`(`name`, `serviceId` 오름차순)이고 DB가 정렬한다. 테이블 콜레이션은 `utf8mb4_0900_ai_ci`로 Java `String.compareTo`와 순서가 다르다(대소문자를 같게 보고, 기호·공백은 UCA 가중치 순). 이름에 영문이 있는 혜택이 209건이다.
- `DEC-MATCH`: 순서는 부합 → 확인 필요, 동률은 이름과 서비스 ID 순이며 검색의 이름·ID 순과 맞췄다.
- `DEC-SEARCH`: 페이지는 번호·크기 방식, 기본 20건·최대 100건, 범위를 벗어난 번호·크기는 잘못된 입력, 마지막 쪽을 넘은 번호는 빈 목록. 응답에 전체 건수를 담되 이후 뺄 수 있다. 목록에서 사라진 혜택은 숨기지 않는다.
- `BenefitSearchRequest`의 `DEFAULT_SIZE`·`MAX_SIZE`는 public이고, 오류 문구 상수(`PAGE_RANGE`, `SIZE_RANGE`)는 패키지 전용이다.
- `MatchResponse`(#58)의 `notices`는 불일치이면 `NOT_FINAL`만, 그 밖에는 세 가지 모두다. 목록에는 불일치가 없으므로 모든 항목의 `notices`가 같다. `uncomparedConditions`도 혜택마다 같은 고정 목록이다.
- 인증: `/api/me/**`는 공개 경로가 아니고 사용자 ID는 `@CurrentUserId`로만 받는다. 계정이 없으면 401 `UNAUTHENTICATED`다(결정 0018, `MatchService.match`).
- 테스트 stub fixture에는 세 서비스가 있고 사용자구분이 모두 `개인`이다. `Gov24StubServer.records(endpoint)`는 바꿀 수 있는 목록이라 레코드를 복사해 서비스를 더할 수 있다.

## 설계 결정

1. **요청마다 전체 혜택을 판정하고 메모리에서 페이지를 자른다.** 2026-10-23 사용자 결정(방안 A).
   - 판정이 상세 비교와 같은 `ConditionMatcher` 하나라 목록과 상세의 결과가 구조적으로 같다. 마이그레이션이 없고 결정 0008을 그대로 따른다. 프로필을 바꾸면 다음 요청에 바로 반영된다.
   - 비용은 쪽 번호와 무관하게 전체 혜택 수에 비례한다. 쪽 사이의 일관성은 검색의 번호·크기 방식과 같은 수준이다(사이에 프로필 수정·수집·날짜 변경이 있으면 항목이 밀릴 수 있다).
   - 대안: 파싱한 지원조건을 메모리에 캐시(B), 지원조건을 정규화 저장하고 SQL로 판정·정렬(C), 사용자별 결과를 미리 계산해 저장(D). 측정 없이 고를 근거가 없어 측정·개선 이슈로 넘긴다. C는 판정 로직이 Java와 SQL 두 벌이 되고, D는 만 나이가 날짜에 따라 바뀌어 재계산 시점이 늘어난다.
   - 판정용 최소 프로젝션과 쪽 항목 재조회는 하지 않는다. 엔티티를 그대로 읽는다. 성능 최적화라 기준선을 단순하게 두고 측정 뒤에 판단한다(2026-10-23 사용자 결정).
2. **정렬은 DB에 맡기고 결과별로 안정 분할한다.** 2026-10-23 사용자 결정.
   - 혜택을 `SEARCH_ORDER`로 읽고, 차례로 판정해 부합·확인 필요 두 목록에 담은 뒤 이어 붙인다. 불일치는 버린다.
   - 결과 기준의 안정 정렬과 같아서, 같은 결과 안의 순서가 검색과 같은 콜레이션 순서다. 비교자가 없다.
   - 대안: 메모리에서 `Comparator`로 결과·이름·ID 정렬. 콜레이션 차이로 검색과 순서가 달라진다. `Collator`로 흉내 내도 MySQL 0900 UCA와 같다는 보장이 없다.
   - `SEARCH_ORDER`를 `BenefitQueryService`에서 public으로 열어 같은 상수를 쓴다. 두 API의 정렬이 따로 바뀌지 않게 한다.
3. **목록 항목은 검색 항목 + 결과 + 항목별 판정·이유다.** 2026-10-23 사용자 결정.
   - 항목: `benefit`(`BenefitSummary`), `result`, `items`(비교 항목 5개 각각의 `status`·`reason`).
   - 검색 항목은 필드를 펼치지 않고 `BenefitSummary`를 그대로 담는다. 검색 응답과 같은 스키마·설명을 쓰고 필드가 두 곳에서 따로 바뀌지 않는다.
   - 비교한 값과 원문·공식 링크는 담지 않는다. 목록 화면에서는 이유 코드면 충분하고, 항목을 열면 상세 비교 API에서 본다.
   - 대안: 비교한 값까지 담기, 항목마다 `MatchResponse` 전체. 응답이 커지고 원문 텍스트가 100건씩 실린다.
4. **응답은 검색 응답과 같은 바깥 구조에 공통 값을 한 번 둔다.** 2026-10-23 사용자 결정.
   - `{ totalCount, page, size, judgedOn, notices, uncomparedConditions, items }`.
   - `notices`는 세 가지 모두, `uncomparedConditions`는 고정 목록, `judgedOn`은 요청 하나에 한 번 정한 기준일이다. 항목마다 반복하지 않는다.
   - `totalCount`는 목록에 나오는 혜택(부합 + 확인 필요) 수다. 검색과 같이 이후 뺄 수 있다고 설명한다.
5. **페이지 파라미터는 `match.dto`의 새 요청 record로 받는다.** 2026-10-23 사용자 결정.
   - 기본값·상한은 `BenefitSearchRequest.DEFAULT_SIZE`·`MAX_SIZE`를 쓴다. 오류 문구는 같은 문구로 이 record에 둔다(패키지 전용 상수라 공유하지 않는다).
   - 대안: 문구 상수를 public으로 열기, 공통 페이지 요청 타입 추출. 후자는 플랜 밖 리팩터링이다.
   - 검색과 같은 입력이면 같은 오류라는 것을 테스트로 고정한다.
   - 구현 중 확인: 북마크 목록의 `BookmarkListRequest`가 이미 같은 방식이라 그 형태를 따랐다.
6. **판정 입력 읽기는 #58과 같게 하되 한 번에 읽는다.**
   - 지원조건은 `SUPPORT_CONDITIONS` 경로만 읽어 `serviceId → SupportConditions` 맵을 만든다. 맵에 없는 혜택은 `conditions = null`로 판정해 `CONDITIONS_NOT_COLLECTED`(확인 필요)가 된다. 상세 비교와 같다.
   - 프로필·혜택·지원조건을 한 읽기 트랜잭션에서 읽어 같은 스냅샷을 본다.
   - 목록에서 사라진 혜택도 검색처럼 숨기지 않는다.

## 단계별 구현 계획

1. **저장소·상수**
   - `SourceRecordRepository.findAllByEndpoint(SourceEndpoint endpoint)` 추가.
   - `BenefitQueryService.SEARCH_ORDER`를 `public static final`로 바꾸고 주석에 조건 비교 목록도 같은 순서를 쓴다고 적는다.
2. **DTO** `kr.govbenefit.match.dto`
   - `MatchListRequest(Integer page, Integer size)`: `@Min(1)` page, `@Min(1) @Max(BenefitSearchRequest.MAX_SIZE)` size. 기본값 1, `BenefitSearchRequest.DEFAULT_SIZE`. 오류 문구 `PAGE_RANGE`·`SIZE_RANGE`는 검색과 같은 문구. `@Parameter` 설명은 검색과 같게 쓴다.
   - `MatchListResponse(long totalCount, int page, int size, LocalDate judgedOn, List<MatchNotice> notices, List<UncomparedCondition> uncomparedConditions, List<MatchListItem> items)`.
   - `MatchListResponse.MatchListItem(BenefitSummary benefit, MatchResult result, MatchItemResults items)`.
   - `MatchListResponse.MatchItemResults(MatchItemResult personalTarget, age, gender, lifeAttributes, householdAttributes)`, `MatchItemResult(ItemStatus status, MatchReason reason)`.
     - 중첩 record 이름은 명세 스키마 이름이 되므로 `MatchResponse`의 `MatchItems`와 겹치지 않게 짓는다.
   - `static MatchListItem MatchListItem.of(Benefit benefit, MatchOutcome outcome)`.
   - `@Schema` 설명: 신청 가능 확정이 아님, 불일치는 목록에 없음(검색으로는 찾을 수 있음), 순서 규칙, 이유의 우선순위는 상세 비교와 같음, 비교한 값과 원문은 상세 비교에서 봄.
3. **서비스** `MatchService.list(Long userId, int page, int size)` → `MatchListResponse`
   - 계정이 없으면 `BusinessException(UNAUTHENTICATED)`.
   - `today = LocalDate.now(clock)`, `subject = MatchSubject.of(profile, today)`.
   - `sourceRecordRepository.findAllByEndpoint(SUPPORT_CONDITIONS)` → `Map<String, SupportConditions>`(파싱은 `match`와 같은 `SupportConditions.parse(jsonMapper.readTree(payload))`).
   - `benefitRepository.findAll(BenefitQueryService.SEARCH_ORDER)`를 차례로 판정해 `MATCH`·`NEEDS_CHECK` 목록에 담고 `MISMATCH`는 버린다.
   - 이어 붙인 목록에서 `(page - 1) * size`(long)부터 `size`건을 자른다. 시작이 전체 건수 이상이면 빈 목록.
   - 파싱 코드는 `match`와 공유하도록 private 메서드 하나로 모은다.
4. **컨트롤러** `MatchController`
   - `@GetMapping` `list(@CurrentUserId Long userId, @ParameterObject @Valid MatchListRequest request)` → 200. `security = BEARER_AUTH`.
   - `@ApiResponse`: 200, 400 `INVALID_INPUT`(예시는 검색과 같은 형식), 401 `UNAUTHENTICATED`.
   - 설명: 순서 규칙, 불일치 제외(검색으로는 찾음), 확인 필요는 숨기지 않음, 프로필 미입력이면 개인 대상 혜택이 모두 `NEEDS_CHECK`, 마지막 쪽을 넘은 번호는 빈 배열인 200, 항목의 판정은 상세 비교와 같음.
   - 구현 중 변경: 401 설명과 예시를 상수로 빼 두 API가 같이 쓴다(`BookmarkController`와 같은 방식).
5. **API 명세** `OpenApiConfig.DESCRIPTION`의 조건 비교 항목에 목록(`GET /api/me/matches`)을 더한다. 부합 → 확인 필요 순이고 개인 대상이 아닌 혜택은 나오지 않는다.
6. **제품 문서**: 바꾸지 않는다. 순서 규칙은 이미 `DEC-MATCH`·`REQ-MATCH-01`에 있다.
7. **테스트**
   - `match.controller.MatchListApiTest`(`@SpringBootTest`, MockMvc, Testcontainers, `MutableClock`, `Gov24StubServer`로 실제 수집). `MatchApiTest`의 설정을 따른다. 서비스는 fixture 레코드를 세 경로 모두 복사해 서비스 ID·이름·사용자구분·지원조건을 바꿔 더한다.
     - 부합 → 확인 필요 순이고, 같은 결과 안에서는 이름순, 이름이 같으면 서비스 ID 순이다.
     - 같은 결과 안의 순서가 같은 혜택들의 검색 API 순서와 같다. 대소문자만 다른 영문 이름(`abc …`, `ABD …`, `Abe …`, 그리고 콜레이션상 같은 `ABC …`·`abc …`를 서비스 ID 역순으로)으로 메모리 정렬이면 깨지게 만든다.
     - 연령 경계값 등 확인 필요 혜택이 숨겨지지 않는다.
     - 사용자구분 `법인/시설/단체`·`소상공인` 혜택은 목록에 없고, 같은 혜택을 검색 API로는 찾는다.
     - 사용자구분이 비어 있거나 지원조건을 수집하지 못한 혜택은 확인 필요로 나온다.
     - 프로필을 저장한 적 없는 사용자에게도 목록이 나오고 모든 항목이 확인 필요다.
     - 목록 각 항목의 `result`와 항목별 `status`·`reason`이 같은 혜택의 `GET /api/me/matches/{serviceId}` 결과와 같다.
     - 프로필을 수정한 직후 조회하면 바뀐 값으로 판정된 순서가 나온다.
     - 페이지: 기본 20건·최대 100건, 범위를 벗어난 번호·크기는 검색과 같은 400 응답(`errors` 포함), 마지막 쪽을 넘은 번호는 빈 배열, 쪽 경계에서 중복·누락이 없다, `totalCount`는 불일치를 뺀 건수다.
     - 응답 최상위의 `judgedOn`·`notices`·`uncomparedConditions`.
     - A와 B의 프로필이 다르면 각자의 목록이 다르고, 요청에 `userId`를 넣어도 토큰 주인으로 판정한다. 토큰이 없으면 401, 탈퇴 뒤 남은 토큰은 401이다.
   - `ApiDocsTest`: `/api/me/matches`의 `get`이 bearer 보안을 요구하고, 응답 코드는 200·400·401·500이며, 요청 파라미터는 `page`·`size`뿐이다(`userId` 없음).
   - 기존 테스트는 바꾸지 않고 통과한다.
   - 구현 중 확인: 정렬을 메모리의 `String` 비교로 바꾸면 순서 테스트가 실패하는 것을 확인했다.

## 완료 조건 매핑

- [ ] 목록이 ‘부합’ → ‘확인 필요’ 순이고, 같은 결과 안에서는 혜택 이름, 이름이 같으면 서비스 ID 순이다. → 설계 2. `MatchListApiTest`의 순서·검색 순서 비교.
- [ ] ‘확인 필요’ 혜택이 자동으로 숨겨지지 않는다. → 설계 1·6. `MatchListApiTest`의 확인 필요·미수집·사용자구분 없음.
- [ ] 개인 대상이 아닌 혜택은 목록에 나오지 않고, 같은 혜택을 검색 API로는 찾을 수 있다. → 설계 2. `MatchListApiTest`의 불일치 제외.
- [ ] 프로필을 하나도 입력하지 않은 사용자에게도 목록이 나오고, 개인 대상 혜택은 모두 ‘확인 필요’다. → 설계 6, #58 설계 5(a). `MatchListApiTest`의 프로필 없음.
- [ ] 목록 항목의 결과가 같은 혜택의 상세 비교 결과와 같다. → 설계 1·3. `MatchListApiTest`의 상세 비교 대조.
- [ ] 프로필을 수정한 직후 조회하면 바뀐 값으로 판정된 순서가 나온다. → 설계 1. `MatchListApiTest`의 프로필 수정.
- [ ] 페이지 번호·크기의 기본값, 상한, 잘못된 입력, 마지막 쪽을 넘은 번호의 처리가 검색 API(`DEC-SEARCH`)와 같다. → 설계 4·5. `MatchListApiTest`의 페이지, `ApiDocsTest`.
- [ ] 사용자 A의 프로필이 사용자 B의 목록에 영향을 주지 않고, 인증되지 않은 요청은 거절된다. → 결정 0018. `MatchListApiTest`의 사용자 분리·인증, `ApiDocsTest`.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인: `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤 가입·로그인으로 액세스 토큰을 받고 프로필을 저장한다.
  ```sh
  curl -s 'localhost:8080/api/me/matches' -H "Authorization: Bearer $TOKEN"
  curl -s 'localhost:8080/api/me/matches?page=2&size=100' -H "Authorization: Bearer $TOKEN"
  curl -s 'localhost:8080/api/me/matches?page=999' -H "Authorization: Bearer $TOKEN"
  curl -si 'localhost:8080/api/me/matches?size=200' -H "Authorization: Bearer $TOKEN"
  curl -si 'localhost:8080/api/me/matches'
  ```
  다음을 확인한다.
  - 부합 항목이 먼저 나오고 그 뒤에 확인 필요 항목이 나온다.
  - `totalCount`가 약 9,900건(불일치 약 1,000건 제외)이다.
  - 마지막 쪽을 넘은 번호는 빈 `items`이고, `size=200`은 400, 토큰이 없으면 401이다.

## 결정 필요

없음
