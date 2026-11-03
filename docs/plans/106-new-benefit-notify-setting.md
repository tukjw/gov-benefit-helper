status: in-progress

# 구현 계획 — #106 로그인한 사용자의 새 혜택 알림 켜기·끄기 설정 제공

브랜치: `feat/106-new-benefit-notify-setting` (develop `bad3e28` 기준)

## 목표 및 범위

- 목표: 로그인한 사용자가 자기 새 혜택 알림 설정을 조회하고 켜기·끄기로 바꾼다(`REQ-NOTIFY-03` P0의 동의, `DEC-NOTIFY(새 혜택 알림)`).
- 포함:
  - `GET /api/me/notification-settings`(조회, 200), `PATCH /api/me/notification-settings`(변경, 204).
  - `user_account.new_benefit_notify` 컬럼(V12). 기본값은 꺼짐이다.
  - 새 도메인 패키지 `kr.govbenefit.notification`(컨트롤러·서비스·DTO).
  - CORS 허용 메서드에 PATCH를 더하고, 결정 0029를 대체하는 결정 0040을 쓴다.
  - API 명세, `ApiDocsTest`, `CorsTest`, 탈퇴 테스트.
- 제외(이슈 본문):
  - 알림 대상 선정과 발송(후속 이슈). 대상 선정에 쓸 인덱스도 그때 정한다.
  - 설정 화면(후속 이슈). 프론트 `HttpMethod`에 PATCH를 넣는 것도 그때 한다.
  - 기한·변경 알림 설정(보류, #103).
  - 설정을 바꾼 시각과 이력. 완료 조건과 요구사항에 없다.

## 확인된 사실과 제약

- `DEC-NOTIFY(새 혜택 알림)`: 동의는 새 혜택 알림 켜기·끄기 하나이며 관심 저장이나 다른 알림 설정과 별개다. `DEC-AUTH(로그인·탈퇴)`: 탈퇴하면 알림 설정을 즉시 삭제하고, 같은 이메일로 다시 가입하면 새 사용자다.
- 인증 경계: `/api/me/**`는 공개 경로가 아니고, 개인 API는 사용자 ID를 `@CurrentUserId`로만 받는다(결정 0018). 요청 본문의 모르는 필드는 무시된다(`ProfileApiTest`가 본문의 `userId`·`id`로 확인한다).
- 남은 액세스 토큰: 토큰 검증은 DB를 보지 않으므로 서비스가 계정 존재를 확인해 없으면 401 `UNAUTHENTICATED`를 낸다(`AccountService.me`, `ProfileService`, `BookmarkService`).
- 탈퇴: `AccountService.withdraw`가 `findByIdForUpdate`로 계정 행을 잠그고 지운다. 계정 행의 컬럼은 행과 함께 사라진다.
- CORS: `CorsConfig`는 GET·POST·PUT·DELETE만 허용한다(결정 0029). `CorsTest`가 허용 메서드 문자열 `GET,POST,PUT,DELETE`를 그대로 비교한다. MockMvc API 테스트는 사전 요청을 거치지 않으므로, PATCH가 막혀 있어도 API 테스트는 통과한다.
- 결정 대체: 채택된 결정의 본문은 고치지 않고 새 번호로 대체한다(`docs/decisions/README.md`). 0027을 0029로 대체할 때(#73) 코드·제품 문서·운영 문서의 참조를 새 번호로 바꿨다.
- 0029를 가리키는 곳:
  - 백엔드: `CorsConfig`, `CsrfProperties`, `CorsTest`, `RefreshTokenApiTest`.
  - 프론트 주석: `vite.config.ts`, `src/router.tsx`, `src/lib/config.ts`, `src/lib/api/client.ts`.
  - 문서: `docs/product/development.md`의 `DEC-APP`, `docs/ops/deploy.md`.
  - 결정 문서 0034·0036·0037·0039의 본문에도 있지만 고치지 않는다.
- 스키마는 Flyway가 만들고 Hibernate는 검증만 한다(`ddl-auto: validate`). 기존 `BOOLEAN` 컬럼(`benefit.apply_period_needs_review`)은 `Boolean` 필드로 검증을 통과한다. 다음 마이그레이션 번호는 V12다.
- 계정 값 변경은 엔티티를 읽어 메서드로 고치는 방식이다(`UserAccount.changePassword`).

## 설계 결정

1. **설정은 `user_account.new_benefit_notify BOOLEAN NOT NULL DEFAULT FALSE`에 둔다.** 2026-11-03 사용자 결정.
   - 새 테이블도, 처음 저장할 때 만드는 행도 없다. 기존 계정도 기본값으로 꺼짐이다.
   - 탈퇴하면 계정 행과 함께 지워지므로 `AccountService.withdraw`와 FK를 바꾸지 않는다.
   - 대안: 별도 테이블(사용자당 한 행, `UserProfile`과 같은 방식). 알림 데이터가 계정 테이블과 나뉘지만, 행 없음과 `false`를 함께 꺼짐으로 다뤄야 하고 첫 저장 경합을 처리해야 한다.
   - 대안: 구독 테이블(행이 있으면 켜짐). 테이블이 곧 켠 사용자 목록이지만, 끈 기록이 남지 않는다.
   - 대안: `user_profile` 컬럼. 프로필 PUT의 전체 교체와 충돌하고, 프로필이 없는 사용자는 빈 행을 만들어야 한다.
   - 한계: 인증 테이블에 알림 동의가 섞인다. 사용자 단위 알림 설정이 늘면 컬럼이 늘어난다. 기한·변경 알림은 관심 혜택 단위 동의일 가능성이 커서 이 컬럼과 따로 정한다.
   - 결정 문서로 올리지 않는다. 후속 대상 선정은 이 컬럼을 읽기만 하고, 따라야 할 규칙이 생기지 않는다.
2. **조회는 `GET /api/me/notification-settings`로 200과 현재 설정 `{"newBenefit": boolean}`이고, 변경은 같은 경로의 `PATCH`로 본문 없는 204다.** 2026-11-03 사용자 결정.
   - PATCH는 본문에 있는 항목만 바꾼다. 항목이 늘어도 빠진 항목은 그대로 두면 되므로, PUT 전체 교체처럼 빠진 필드를 어떻게 볼지 따로 정할 필요가 없다.
   - 지금은 항목이 하나라 `newBenefit`이 없거나 `null`이면 바꿀 것이 없는 요청이다. 400 `INVALID_INPUT`으로 거절한다. 항목이 늘 때 다시 정한다.
   - 같은 값을 다시 보내도 204와 같은 결과다.
   - 본문 형식은 다른 API와 같은 `application/json`이다. JSON Merge Patch(`application/merge-patch+json`, RFC 7396)는 쓰지 않는다. `null`을 항목 삭제로 읽는 규칙이 켜기·끄기에 맞지 않는다.
   - 변경 응답에 본문이 없다. 화면은 보낸 값을 그대로 쓰면 된다. 북마크 저장·해제(`PUT`·`DELETE /api/me/bookmarks/{serviceId}`)와 같다.
   - 대안: 200과 현재 설정(`PUT /api/me/profile`과 같음). 항목이 늘면 일부만 바꾼 뒤 전체 설정을 다시 조회하지 않아도 되지만, 지금은 항목이 하나라 보낸 값이 곧 결과다.
   - 대안: PUT. 추가로 바꿀 것은 없지만, 항목이 늘면 빠진 필드 규칙이 필요하다.
   - 대안: POST. HTTP 의미상 멱등이 아니고, 이 저장소에서 POST는 로그인·탈퇴 같은 동작에만 쓴다.
   - 경로는 계정과 나눈다. `PATCH /api/me`로 하면 계정 응답(`AccountResponse`)에 설정이 섞이고, 저장 위치를 옮길 때 API도 바뀐다.
3. **CORS 허용 메서드에 PATCH를 더하고, 결정 0029를 0040으로 대체한다.** 설계 2(2026-11-03 사용자 결정)에 따른다.
   - 0040은 0029의 결정을 그대로 옮기고 CORS 허용 메서드에 PATCH만 더한다.
   - 0029의 상태 줄과 `docs/decisions/README.md`의 목록 표를 고친다.
   - 0029를 가리키는 코드·제품 문서·운영 문서의 참조를 0040으로 바꾼다(#73과 같다). 다른 결정 문서와 플랜의 본문은 고치지 않는다.
   - 프론트 주석 4개 파일이 바뀌어 CI의 `frontend` 검사가 돈다. 동작은 바뀌지 않는다.
4. **조회·변경 모두 `findById`로 계정을 읽고, 없으면 401 `UNAUTHENTICATED`다. 변경은 엔티티 메서드로 한다.**
   - `changePassword`와 같은 방식이다. 같은 값이면 Hibernate가 UPDATE를 보내지 않는다.
   - 탈퇴 뒤 남은 토큰으로 보낸 요청은 계정이 없어 401이다.
   - 한계: 같은 사용자의 요청이 동시에 겹치는 경우는 대비하지 않는다(2026-11-03 사용자 결정). 계정을 읽은 뒤 탈퇴가 커밋되면 500이 되고(데이터는 남지 않는다), 비밀번호 재설정과 동시에 바꾸면 재설정이 읽은 시점의 값으로 덮을 수 있다.
5. **패키지 `kr.govbenefit.notification`에 컨트롤러·서비스·DTO를 두고, 값은 `UserAccount`·`UserAccountRepository`로 읽고 쓴다.**
   - 후속 대상 선정·발송이 같은 패키지에 붙는다. `ProfileService`·`BookmarkService`도 계정 저장소를 직접 쓴다.
   - 대안: `account` 패키지(`MeController`)에 둠. 알림 API가 계정 기능 사이에 섞인다.
   - 이름: 엔티티 필드 `newBenefitNotify`, 컬럼 `new_benefit_notify`, API 필드 `newBenefit`. API는 알림 설정 리소스 안이라 `Notify`를 빼도 뜻이 같다.
6. **탈퇴할 때 따로 지우지 않는다.** 설정은 계정 행과 함께 지워지고(`DEC-AUTH`), 다시 가입한 계정은 새 행이라 꺼짐이다.

## 단계별 구현 계획

1. **마이그레이션** `V12__add_user_account_new_benefit_notify.sql`
   ```sql
   -- 새 혜택 알림 켜기·끄기(#106, DEC-NOTIFY(새 혜택 알림)). 설정한 적이 없으면 꺼짐이다. 탈퇴하면 계정 행과 함께 지워진다.
   ALTER TABLE user_account
       ADD COLUMN new_benefit_notify BOOLEAN NOT NULL DEFAULT FALSE AFTER password_hash;
   ```
2. **엔티티** `account.domain.UserAccount`
   - `private boolean newBenefitNotify;`를 더한다. 주석: 새 혜택 알림을 켰는지(`DEC-NOTIFY`). 가입하면 꺼짐이다.
   - `public void changeNewBenefitNotify(boolean enabled)`를 더한다.
   - `create`는 바꾸지 않는다. 기본값이 `false`다.
3. **DTO** `notification.dto`
   - `NotificationSettingRequest(Boolean newBenefit)`: `@NotNull(message = "새 혜택 알림을 켤지 끌지 보내야 합니다.")`, `@Schema(requiredMode = REQUIRED)`.
   - `NotificationSettingResponse(boolean newBenefit)`: 설명은 "새 혜택 알림을 켰는지. 설정한 적이 없으면 `false`".
4. **서비스** `notification.service.NotificationSettingService`
   - 공통: `userAccountRepository.findById(userId)`가 없으면 `BusinessException(UNAUTHENTICATED)`.
   - `@Transactional(readOnly = true) NotificationSettingResponse get(Long userId)`: 읽은 계정의 값으로 응답을 만든다.
   - `@Transactional void update(Long userId, NotificationSettingRequest request)`: `account.changeNewBenefitNotify(request.newBenefit())`.
   - 클래스 주석: `REQ-NOTIFY-03`, `DEC-NOTIFY`, 결정 0018. 관심 저장과 별개라는 점.
5. **컨트롤러** `notification.controller.NotificationSettingController`
   - `@RequestMapping("/api/me/notification-settings")`, `@Tag(name = "알림 설정")`.
   - `@GetMapping` → 200. `@PatchMapping`(`@Valid @RequestBody NotificationSettingRequest`) → 204.
   - 둘 다 `security = BEARER_AUTH`를 붙인다.
   - `@ApiResponse`는 `ProfileController`와 같은 표기다. 조회는 200·401, 변경은 204·400·401이다.
6. **CORS** `common.security.CorsConfig`
   - `setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"))`.
   - 주석의 결정 번호를 0040으로 바꾼다.
7. **API 명세** `OpenApiConfig.DESCRIPTION`
   - 첫 줄의 API 목록에 알림 설정을 넣는다.
   - 항목을 하나 더한다: 새 혜택 알림 설정은 `GET /api/me/notification-settings`로 조회하고 `PATCH`로 본문의 항목만 바꾼다. 설정한 적이 없으면 꺼짐(`false`)이고, 같은 값을 다시 보내도 결과가 같다. 북마크는 알림 동의가 아니다.
   - 탈퇴 항목의 삭제 대상에 알림 설정을 넣는다. `MeController`의 탈퇴 설명도 같이 고친다.
8. **결정 문서**
   - `docs/decisions/0040-frontend-static-spa-cors-methods.md`(템플릿을 따른다).
     - 제목: 웹 프론트 정적 SPA 구조와 API 호출(PATCH 허용).
     - 배경: #106의 설정 변경이 부분 변경(PATCH)이라, 브라우저 사전 요청에서 막히지 않게 허용한다.
     - 결정·근거·적용 위치·영향은 0029에서 옮기고, CORS 메서드 줄에 PATCH를 넣는다.
     - 근거에 PATCH를 고른 이유(설계 2)를 더한다.
     - 영향에 한 줄을 더한다: PATCH를 부르는 화면을 만들 때 프론트 `HttpMethod`에 PATCH를 넣는다.
   - `0029`의 상태 줄을 `대체됨 → [0040](0040-frontend-static-spa-cors-methods.md)`으로 바꾼다.
   - `docs/decisions/README.md`의 목록 표: 0029 상태를 바꾸고 0040 행을 더한다.
   - 참조를 0040으로 바꾼다: 백엔드 `CorsConfig`·`CsrfProperties`·`CorsTest`·`RefreshTokenApiTest`, 프론트 `vite.config.ts`·`src/router.tsx`·`src/lib/config.ts`·`src/lib/api/client.ts`, `docs/product/development.md`의 `DEC-APP`, `docs/ops/deploy.md`.
9. **테스트**
    - `notification.controller.NotificationSettingApiTest`(`@SpringBootTest`, MockMvc, Testcontainers, `MutableClock`). 계정은 `ProfileApiTest`처럼 `UserAccountRepository`로 만들고, 토큰은 `AccessTokenIssuer`로 발급한다.
      - 설정한 적 없는 계정의 조회는 200 `{"newBenefit": false}`이고, 응답 필드는 `newBenefit` 하나다.
      - 켜면 본문 없는 204이고 조회가 `true`다. 다시 켜도 204이고 `true`다. 끄면 `false`, 다시 꺼도 `false`다. 각 단계에서 DB 값도 확인한다.
      - `{}`, `{"newBenefit": null}`, 본문 없음, 깨진 JSON은 400 `INVALID_INPUT`이고 값이 그대로다.
      - 켠 상태에서 북마크를 저장·해제해도 `true`이고, 끈 상태에서 저장해도 `false`다. 혜택 행은 `WithdrawalApiTest`처럼 `BenefitRepository`로 만든다.
      - 본문에 `userId`·`id`로 B를 넣어 A가 켜면 A만 `true`이고 B는 `false`다. 조회에 `userId` 쿼리 파라미터를 넣어도 A의 값이다.
      - 토큰 없이 보낸 조회·변경은 401이고 값이 그대로다.
      - 계정을 지운 뒤 그 토큰으로 보낸 조회·변경은 401이다. 500이 나지 않는다.
    - `WithdrawalApiTest`
      - `탈퇴_뒤_남은_액세스_토큰으로는_개인_API를_쓸_수_없다`에 알림 설정 조회·변경의 401을 더한다.
      - 같은 이메일로 다시 가입하는 테스트에서, 탈퇴 전에 알림을 켜고 다시 가입한 사용자의 조회가 `false`인지 확인한다.
      - `USER_TABLES`는 바꾸지 않는다. 설정은 계정 행의 컬럼이다.
    - `CorsTest`
      - 허용 메서드 문자열을 `GET,POST,PUT,PATCH,DELETE`로 바꾼다.
      - `/api/me/notification-settings`의 PATCH 사전 요청이 토큰 없이 통과하고 PATCH를 허용한다.
    - `ApiDocsTest`: `알림_설정_API는_액세스_토큰을_요구하고_사용자_ID를_받지_않는다`
      - `get`·`patch`가 bearer 보안을 요구한다.
      - 응답 코드: 조회는 200·401·500, 변경은 204·400·401·500이다. 변경의 204에는 본문 스키마가 없다.
      - 요청 스키마의 속성은 필수인 `newBenefit` 하나이고, 파라미터에 `userId`가 없다.
    - 기존 테스트는 위의 변경 외에는 바꾸지 않고 통과한다.
10. **문서**
    - `docs/product/development.md`의 `DEC-AUTH` 행은 이미 "탈퇴하면 알림 설정을 즉시 삭제"를 담고 있어 바꾸지 않는다. `DEC-APP` 행의 결정 링크만 바꾼다(8단계).

## 완료 조건 매핑

- [ ] 가입 후 설정한 적이 없는 사용자의 설정은 꺼짐으로 조회된다. → 설계 1. `NotificationSettingApiTest`의 첫 조회.
- [ ] 켜면 켜짐, 끄면 꺼짐으로 조회되고, 같은 값을 다시 보내도 결과가 같다. → 설계 2·4. `NotificationSettingApiTest`의 켜기·끄기 반복.
- [ ] 관심 혜택을 저장·해제해도 알림 설정은 바뀌지 않는다. → 설계 1. `NotificationSettingApiTest`의 북마크 저장·해제.
- [ ] 요청에 다른 사용자 ID를 넣어도 그 사용자의 설정을 읽거나 바꿀 수 없다. → 결정 0018. `NotificationSettingApiTest`의 `userId`·`id`, `ApiDocsTest`.
- [ ] 인증되지 않은 조회·변경 요청은 거절된다. → 결정 0018, 설계 4. `NotificationSettingApiTest`의 토큰 없음·계정 없는 토큰, `WithdrawalApiTest`의 남은 토큰.
- [ ] 탈퇴한 사용자의 설정이 삭제되고, 같은 이메일로 다시 가입한 사용자의 설정은 꺼짐이다. → 설계 6. `WithdrawalApiTest`의 계정 행 삭제와 재가입.

## 검증 명령

- `backend/`에서 `./gradlew test`
- `frontend/`에서 `npm run lint`, `npm run typecheck`, `VITE_API_BASE_URL=http://localhost:8080 npm run build`(CI와 같은 값). 주석만 바뀌지만 CI `frontend` 검사 대상이다.
- 로컬 확인: `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤 가입·로그인으로 액세스 토큰을 받고 다음을 실행한다.
  ```sh
  curl -si localhost:8080/api/me/notification-settings -H "Authorization: Bearer $TOKEN"
  curl -si -X PATCH localhost:8080/api/me/notification-settings -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{"newBenefit":true}'
  curl -si -X PATCH localhost:8080/api/me/notification-settings -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{"newBenefit":true}'
  curl -si localhost:8080/api/me/notification-settings -H "Authorization: Bearer $TOKEN"
  curl -si -X PATCH localhost:8080/api/me/notification-settings -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{}'
  curl -si -X OPTIONS localhost:8080/api/me/notification-settings -H 'Origin: http://localhost:3000' \
    -H 'Access-Control-Request-Method: PATCH' -H 'Access-Control-Request-Headers: authorization, content-type'
  ```
  다음을 확인한다.
  - 첫 조회가 `{"newBenefit":false}`다.
  - 두 번의 켜기가 모두 204이고, 그 뒤 조회가 `{"newBenefit":true}`다.
  - `{}`가 400 `INVALID_INPUT`이다.
  - 사전 요청이 200이고 `Access-Control-Allow-Methods`에 PATCH가 있다.

## 결정 필요

없음
