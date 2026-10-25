status: in-progress

# 구현 계획 — #67 비회원도 프로필을 입력해 조건 비교 결과 확인

브랜치: `feat/67-guest-condition-match` (develop `398ff42` 기준)

## 목표 및 범위

- 목표: 로그인하지 않은 사람이 프로필 값을 요청에 담아 보내면, 저장 없이 로그인 사용자와 같은 규칙의 조건 비교 목록과 상세 결과를 받는다. 로그인은 프로필 저장·관심 혜택처럼 기록을 남기는 기능에만 필요하게 한다.
- 포함:
  - 공개 조건 비교 목록 `POST /api/matches`와 상세 `POST /api/matches/{serviceId}`. 본문은 프로필 저장 요청(`ProfileRequest`)과 같은 형식이고, 목록의 `regionScope`·`page`·`size`는 쿼리다.
  - 입력 검증은 `ProfileValidator`를 그대로 쓴다.
  - 프로필 선택지 API를 공개 경로 `GET /api/profile/options`로 옮기고 `GET /api/me/profile/options`는 없앤다. 2026-10-25 사용자 결정으로 이번 이슈에 넣었다(이슈 작업 내용에는 없음). 생애·가구 속성의 값과 이름이 이 API에만 있어, 옮기지 않으면 로그인하지 않은 클라이언트가 입력 선택지를 만들 수 없다.
  - 공개 경로 추가(`SecurityConfig.PUBLIC_PATHS`), API 명세, 제품 문서(`overview.md`의 2차 범위, `REQ-MATCH-01`, `REQ-PROFILE-01`, DEC-AUTH 공개 범위) 갱신.
- 제외(이슈 본문):
  - 웹 화면(F3).
  - 비회원 입력값을 가입 후 프로필로 옮기기.
  - 비회원 요청량 제한(DEC-AUTH에 따라 O2·O3).
  - 응답 시간 측정과 성능 개선.

## 확인된 사실과 제약

- 결정 0018: API는 기본으로 막고 로그인 없이 쓰는 경로만 `SecurityConfig.PUBLIC_PATHS`에 둔다. 공개 경로에 유효하지 않은 토큰을 보내면 401이고, 유효한 토큰은 통과한다. 새 공개 API는 `PUBLIC_PATHS`에 경로를 추가하고 토큰 없이 호출하는 테스트를 둔다(0018 "영향"). 개인 API는 `@CurrentUserId`로만 사용자를 정한다.
- `CurrentUserIdArgumentResolver`는 토큰이 없으면 `IllegalStateException`을 던진다. 공개 컨트롤러에서는 쓰지 않는다.
- `MatchService.match(userId, serviceId)`와 `list(userId, requested, page, size)`는 `requireAccount`로 계정을 확인한 뒤 `MatchSubject.of(UserProfile, today)`로 비교 대상을 만든다. 그 뒤의 판정(`ConditionMatcher.match`)·지역 범위(`scope`, `AgencyRegion.isWithin`)·정렬·페이지는 `MatchSubject`만 쓴다. 클래스 전체가 `@Transactional(readOnly = true)`다.
- `ProfileValidator.validate(ProfileRequest)`는 항목 오류를 모두 모아 `InvalidInputException`으로 던지고, 통과하면 `ProfileValues`를 돌려준다. `ProfileService.put`은 계정 확인보다 검증을 먼저 한다.
- `ProfileValues`의 속성은 `Collectors.toSet()`으로 만든 순서 없는 `Set`이다. 저장된 프로필의 `UserProfile.lifeAttributes()`·`householdAttributes()`는 enum 선언 순서의 `List`이고, 값이 없으면 null이다. 상세 응답의 `profileAttributes`는 이 순서를 그대로 내므로, 비회원 경로도 같은 순서로 맞춰야 응답이 같다.
- `MatchListRequest`는 `@ParameterObject @Valid`로 쿼리를 받고, 쪽 번호·크기의 범위와 기본값은 `BenefitSearchRequest`와 같다(`DEC-SEARCH`).
- 선택지 응답은 `ProfileService.options()`가 `RegionService.sidos()`와 `AttributeOption.of(...)`로 만든다. DB는 `region`만 읽는다.
- 선택지 경로를 참조하는 곳: `ProfileController`(매핑과 `PUT` 설명), `OpenApiConfig.DESCRIPTION`, `ProfileApiTest`(272·364행), `ApiDocsTest`(360행), 결정 0021의 "적용 위치". 프론트(`frontend/`)는 아직 쓰지 않는다. 채택된 결정 문서 본문은 고치지 않으므로(`docs/decisions/README.md`) 0021의 옛 경로는 남는다.
- 프로필 기록 테이블은 `user_profile`, `user_profile_attribute`이고 계정은 `user_account`다.
- `MatchListApiTest`·`MatchApiTest`는 stub 수집 데이터와 `SignUpSteps`로 로그인 사용자의 비교를 검증한다. 같은 fixture로 비회원 결과와 로그인 결과를 나란히 비교할 수 있다.

## 설계 결정

2026-10-25 사용자 결정. 대안과 장단점은 같은 날 대화에서 비교했다.

1. **비회원 비교는 새 공개 경로 `/api/matches`에 둔다.**
   - `PUBLIC_PATHS`에 `/api/matches/**`를 추가한다. 0018을 대체하지 않는다.
   - 기존 `/api/me/matches`는 바꾸지 않는다.
   - 경로에 `guest`를 넣지 않는다. 유효한 토큰이 붙어 와도 통과하고 본문 값으로만 비교하므로, 로그인 사용자도 저장하지 않은 값으로 비교해 볼 수 있다. 토큰이 있어도 저장된 프로필을 읽지 않는다.
   - 대안: `/api/me/matches`를 인증 선택으로 바꿈(`/api/me`에 비회원 요청이 섞여 0018 대체가 필요하고, 토큰과 본문이 함께 올 때의 규칙이 생긴다), `/api/benefits/**` 아래에 둠(공공 정보 조회와 개인 입력 판정이 섞이고 `/api/benefits/{serviceId}`와 경로가 겹친다).
2. **프로필 값은 POST 본문으로 받는다.**
   - 본문은 `ProfileRequest`이며 검증은 `ProfileValidator`다. 미입력(`null`), 빈 배열 거절, `["NONE"]` 해당 없음, 오류 형식이 프로필 저장과 같다.
   - 본문이 없거나 `{}`이면 모든 항목이 미입력이다(`@RequestBody(required = false)`).
   - 아무것도 만들지 않는 조회이며 응답은 200이다. 명세에 저장하지 않는다고 적는다.
   - 생년월일이 URL·접근 로그·브라우저 기록에 남지 않는다.
   - 대안: GET 쿼리(개인 정보가 URL에 남고, 빈 배열과 미입력을 쿼리로 나누기 어렵다).
3. **판정은 `MatchSubject` 기준의 한 경로로 모은다.**
   - `MatchService`의 판정·범위·정렬·페이지를 `MatchSubject`를 받는 private 메서드로 뽑고, 로그인 경로와 비회원 경로가 모두 그 메서드를 쓴다.
   - 비회원 경로는 `ProfileValues`에서 `MatchSubject`를 만든다. 속성은 enum 선언 순서의 `List`로, 없으면 null로 바꿔 저장된 프로필과 같은 값을 만든다.
   - 엔티티(`UserProfile`)를 만들지 않으므로 영속화될 경로가 없다.
   - 대안: 저장하지 않은 `UserProfile`을 만들어 넘김(영속화 위험), 비회원 전용 서비스(규칙이 두 벌).
4. **검증과 혜택 존재 확인의 순서**
   - 상세는 본문 검증을 먼저 하고 혜택을 찾는다. 잘못된 값과 없는 서비스 ID가 함께 오면 400이다. `ProfileService.put`이 계정 확인보다 검증을 먼저 하는 것과 같다.
   - 목록은 쿼리(`MatchListRequest`)의 Bean Validation이 컨트롤러 진입 전에 먼저 거절하고, 그 뒤 본문을 검증한다. 쿼리 오류와 본문 오류를 한 응답으로 모으지 않는다.
5. **응답은 로그인 경로와 같은 형식이다.**
   - `MatchListResponse`, `MatchResponse`를 그대로 쓴다. 응답의 `profile*` 항목에는 요청 값(검증 후 값)이 들어간다.
   - 같은 프로필 값이면 두 경로의 응답 본문이 같다(완료 조건 2).
6. **프로필 선택지는 `GET /api/profile/options`로 옮긴다.**
   - `PUBLIC_PATHS`에 `/api/profile/options`만 정확히 추가한다. 이후 `/api/profile` 아래에 다른 API가 생겨도 열리지 않는다.
   - `GET /api/me/profile/options`는 없앤다. 프론트 사용처가 없어 깨지는 클라이언트가 없다. 옛 경로는 처리할 API가 없으므로 0018에 따라 토큰과 관계없이 404다.
   - 응답 형식(`ProfileOptionsResponse`)은 바꾸지 않는다.
   - 대안: 경로를 두고 `PUBLIC_PATHS`에 `/api/me/profile/options`를 추가(`/api/me`에 공개 경로 예외가 생긴다).

## 단계별 구현 계획

1. **보안 경계** `common.security.SecurityConfig`
   - `PUBLIC_PATHS`에 `/api/matches/**`, `/api/profile/options`를 추가한다. 클래스 주석은 그대로다.
2. **비교 대상** `match.domain.MatchSubject`
   - `static MatchSubject of(ProfileValues values, LocalDate today)`: 생년월일 → 만 나이, 속성 `Set` → enum 선언 순서의 `List`. 미입력(null)은 null로 둔다. 검증을 통과한 집합은 비어 있지 않으므로 빈 목록은 생기지 않는다.
   - 기존 `of(UserProfile, LocalDate)`는 그대로 둔다.
3. **서비스** `match.service.MatchService`
   - `private MatchResponse match(MatchSubject subject, String serviceId, LocalDate today)`, `private MatchListResponse list(MatchSubject subject, RegionScope requested, int page, int size, LocalDate today)`로 기존 본문을 옮긴다.
   - 기존 `match(Long userId, String serviceId)`, `list(Long userId, ...)`는 `requireAccount` → `subject(userId, today)` → 위 메서드 호출로 바꾼다. 동작은 같다.
   - `public MatchResponse matchWith(ProfileRequest profile, String serviceId)`, `public MatchListResponse listWith(ProfileRequest profile, RegionScope requested, int page, int size)`: `ProfileValidator.validate` → `MatchSubject.of(values, today)` → 위 메서드. 클래스의 `readOnly` 트랜잭션을 그대로 쓴다.
   - 클래스 주석에 비회원 경로(요청 값으로 비교하고 저장하지 않음)를 더한다.
4. **공개 비교 컨트롤러** `match.controller.PublicMatchController`(`@RequestMapping("/api/matches")`, 태그는 `조건 비교`)
   - `POST /api/matches`: `@ParameterObject @Valid MatchListRequest`, `@RequestBody(required = false) ProfileRequest`. null이면 모든 값이 null인 `ProfileRequest`로 바꾼다.
   - 구현 중 변경: 빈 요청은 `ProfileRequest.empty()`로 만든다. `ProfileValues.empty()`와 같은 형태다.
   - `POST /api/matches/{serviceId}`: 같은 본문.
   - 명세: 보안 요구 없음. 응답 200·400(`INVALID_INPUT`, 프로필 저장과 같은 예시)·404(상세). 설명에 저장하지 않는다는 것, 판정·순서·범위·페이지가 `/api/me/matches`와 같다는 것, 토큰이 있어도 저장된 프로필을 쓰지 않는다는 것을 적는다.
5. **프로필 선택지 이동** `profile.controller`
   - `ProfileController.options()`를 지우고 `ProfileOptionsController`(`@RequestMapping("/api/profile/options")`, 태그 `프로필`)에 `GET` 하나를 둔다. 서비스는 `ProfileService.options()` 그대로다.
   - 명세: 보안 요구 없음, 응답 200. 설명에 비회원 조건 비교와 프로필 저장에서 쓰는 선택지라고 적는다.
   - `ProfileController`의 `PUT` 설명과 `ProfileRequest`의 경로 언급을 새 경로로 고친다.
   - 구현 중 변경: `ProfileRequest`에는 경로 언급이 없었다. 대신 스키마 설명의 "사용자는 토큰으로만 정한다"를 "프로필 저장과 비회원 조건 비교에 쓴다. 사용자 ID는 받지 않는다"로 고쳤다.
6. **API 명세** `OpenApiConfig.DESCRIPTION`
   - 첫 줄 요약에 로그인 없이 쓰는 API로 비회원 조건 비교와 프로필 선택지를 더한다.
   - 프로필 항목의 선택지 경로를 `GET /api/profile/options`로 고친다.
   - 조건 비교 항목에 `POST /api/matches`, `POST /api/matches/{serviceId}`(요청 값으로 비교, 저장하지 않음, 판정 규칙은 같음)를 더한다.
7. **제품 문서** 변경일 2026-10-25와 이유(이슈 배경: 가입 전에 서비스가 쓸 만한지 확인할 수 없음)를 남긴다.
   - `overview.md` 2차 범위: 조건 비교는 로그인 없이도 쓰고, 로그인은 프로필 저장·관심 혜택에 필요하다.
   - `development.md` DEC-AUTH(공개 범위): 비회원 조건 비교와 프로필 선택지를 로그인 없이 쓴다. 비회원이 보낸 프로필 값은 저장하지 않는다. 요청량 제한은 계속 O2·O3이며, 목록이 요청마다 전체 혜택을 판정하므로 인증 없는 호출의 부하를 한계로 적는다. 결정한 사항 앞 문단에 변경 기록 한 줄을 더한다.
   - DEC-AUTH(로그인·탈퇴)의 이유에 있는 "이 서비스는 가입 목적이 곧 개인 기능" 문장은 #47 당시의 이유라 고치지 않는다. 공개 범위 쪽에 바뀐 전제를 적는다.
   - `requirements.md` `REQ-MATCH-01`: 로그인 사용자는 저장된 프로필, 비회원은 요청에 담은 값으로 비교하며 결과 규칙은 같다. `REQ-PROFILE-01`: 프로필 항목·검증 기준을 비회원 비교 입력에도 쓰고, 비회원 값은 저장하지 않는다. 선택지는 로그인 없이 받는다.
8. **테스트**
   - `match.domain` 단위: `MatchSubject.of(ProfileValues, today)`가 같은 값의 `UserProfile`로 만든 것과 같다(속성 순서 포함, 미입력·`NONE`).
   - 구현 중 변경: 목록과 상세가 같은 fixture를 쓰므로 계획한 `PublicMatchListApiTest`와 `PublicMatchApiTest`를 `PublicMatchApiTest` 하나로 합쳤다. 아래 두 항목을 모두 이 클래스에서 검증한다.
   - 목록(`MatchListApiTest`의 fixture 구성을 따른다):
     - 토큰 없이 프로필 값을 보내면 목록이 나온다.
     - 같은 프로필 값이면 `/api/me/matches`와 응답 본문이 같다. 시도·시군구 입력, 시도만 입력, 거주지 없음과 `regionScope` 생략·`MY_REGION`·`ALL`을 조합한다.
     - 본문 없음과 `{}`가 모두 목록을 내고 개인 대상 혜택이 모두 `NEEDS_CHECK`다.
     - 개인 대상이 아닌 혜택이 없다.
     - 쪽 번호·크기의 기본값·최대값·오류가 검색과 같고, 마지막 쪽을 넘은 번호는 빈 목록이다.
     - 지원하지 않는 값·모순된 입력(시도 없는 시군구, 다른 시도의 시군구, 빈 배열, `NONE`과 다른 값, 미래 생년월일, 잘못된 성별)이 `PUT /api/me/profile`과 같은 `errors`로 거절된다.
     - 요청 뒤 `user_account`, `user_profile`, `user_profile_attribute` 행 수가 그대로다.
     - 유효한 토큰을 붙여 보내도 저장된 프로필이 아니라 본문 값으로 비교한다. 유효하지 않은 토큰은 401이다(0018).
   - 상세: 상세가 같은 값의 `/api/me/matches/{serviceId}`와 응답 본문이 같다(운영 기관 지역 항목 포함). 없는 서비스 ID는 404, 잘못된 값과 없는 ID가 함께 오면 400. 요청 뒤 행 수가 그대로다.
   - `MatchListApiTest`·`MatchApiTest`: 기존 테스트가 그대로 통과한다(완료 조건 8).
   - `ProfileApiTest`: 선택지 요청을 `GET /api/profile/options`로 바꾸고, 토큰 없이 200임을 검증한다. 인증 거절 테스트에서 선택지를 뺀다. `GET /api/me/profile/options`는 404다.
   - `ApiDocsTest`: `/api/matches`·`/api/matches/{serviceId}`의 `post`와 `/api/profile/options`의 `get`에 보안 요구가 없고, 본문 스키마가 `ProfileRequest`, 응답 스키마가 기존 비교 응답과 같다. `/api/me/profile/options`가 명세에 없다.

## 완료 조건 매핑

- [ ] 인증 없이 프로필 값을 보내면 조건 비교 목록과 상세 결과가 반환된다. → 설계 1·2, 단계 1·4. `PublicMatchApiTest`.
- [ ] 같은 프로필 값이면 비회원 결과와 로그인 사용자(저장된 프로필) 결과가 운영 기관 지역 항목과 지역 범위 선택을 포함해 목록 순서·항목별 이유까지 같다. → 설계 3·5, 단계 2·3. `PublicMatchApiTest`, `MatchSubjectTest`.
- [ ] 프로필 값을 하나도 보내지 않아도 목록이 나오고, 개인 대상 혜택은 모두 '확인 필요'다. → 설계 2. `PublicMatchApiTest`.
- [ ] 지원하지 않는 값이나 모순된 입력은 프로필 저장 API와 같은 항목별 오류로 거절된다. → 설계 2·4. `PublicMatchApiTest`.
- [ ] 비회원 요청 뒤 DB에 프로필·사용자 기록이 생기지 않는다. → 설계 3(엔티티를 만들지 않음, 읽기 트랜잭션). `PublicMatchApiTest`.
- [ ] 개인 대상이 아닌 혜택은 비회원 목록에도 나오지 않는다. → 설계 3. `PublicMatchApiTest`.
- [ ] 페이지 번호·크기의 처리가 검색 API(`DEC-SEARCH`)와 같다. → 단계 4(`MatchListRequest` 재사용). `PublicMatchApiTest`.
- [ ] 로그인 사용자의 기존 조건 비교 API 동작은 바뀌지 않는다. → 설계 1·3. `MatchListApiTest`, `MatchApiTest`.
- [ ] 제품 문서와 DEC-AUTH에 비회원 조건 비교가 변경일·이유와 함께 반영되어 있다. → 단계 7.

추가(설계 6, 이슈 본문 밖):

- 로그인 없이 `GET /api/profile/options`로 프로필 선택지를 받고, `GET /api/me/profile/options`는 없다. → `ProfileApiTest`, `ApiDocsTest`.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인: `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤
  ```sh
  curl -s 'localhost:8080/api/profile/options' | head -c 300
  curl -s -X POST 'localhost:8080/api/matches?size=1' -H 'Content-Type: application/json' \
    -d '{"birthDate":"1995-03-01","sidoCode":"6410000","sigunguCode":"3740000"}'
  curl -s -X POST 'localhost:8080/api/matches?regionScope=ALL&size=1'
  curl -si -X POST 'localhost:8080/api/matches' -H 'Content-Type: application/json' -d '{"sigunguCode":"3740000"}'
  ```
  다음을 확인한다.
  - 선택지가 로그인 없이 나온다.
  - 수원시 입력의 `regionScope`가 `MY_REGION`이고 `totalCount`가 같은 프로필을 저장한 로그인 사용자의 `GET /api/me/matches`와 같다(#68 확인 값 1,309건).
  - 본문 없는 `ALL` 목록의 `totalCount`가 개인 대상 전체(#68 확인 값 9,936건)다.
  - 시도 없는 시군구는 400이고 `errors`가 프로필 저장과 같다.
  - 요청 전후 `user_profile`, `user_account` 행 수가 같다.
- 구현 중 확인(2026-10-25, 로컬 수집 데이터 10,950건):
  - `./gradlew test`: 53개 클래스, 495개 테스트 통과, 건너뜀 0.
  - 선택지는 로그인 없이 시도 16개와 속성 목록이 나오고, `GET /api/me/profile/options`는 404다.
  - 수원시 입력은 `MY_REGION` 1,309건, 경기도만 입력하면 2,350건, 본문 없이 `MY_REGION`을 보내면 `ALL` 9,936건이다. #68에서 로그인 사용자로 확인한 값과 같다.
  - 시도 없는 시군구는 400이고 `sigunguCode`의 "시도와 함께 입력해야 합니다."다.
  - 상세에서 생애 속성을 `["JOB_SEEKER","EMPLOYEE"]`로 보내면 `profileAttributes`는 선언 순서인 `["EMPLOYEE","JOB_SEEKER"]`다. 없는 서비스 ID는 404다.
  - 요청 전후 `user_account` 3, `user_profile` 1, `user_profile_attribute` 2행으로 같다.

## 결정 필요

없음
