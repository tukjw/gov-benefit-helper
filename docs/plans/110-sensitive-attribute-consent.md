status: in-progress

# 구현 계획 — #110 건강 관련 프로필 속성은 별도 동의를 받아 처리

브랜치: `feat/110-sensitive-attribute-consent` (develop `29a34a1` 기준)

## 목표 및 범위

- 목표: 생애 속성 중 건강에 관한 4개(장애인, 질병/질환자, 임산부, 예비부모/난임)는 별도 동의가 있을 때만 받고, 회원은 동의 시각을 기록한다(개인정보 보호법 제23조).
- 포함:
  - 요청 필드 `sensitiveAttributeConsent`와 항목별 검증. 회원 프로필 저장과 비회원 조건 비교(목록·상세) 모두에 적용한다.
  - `user_profile.sensitive_attribute_consented_at` 컬럼과 기존 4개 속성 삭제(V15).
  - 프로필 응답의 동의 시각, 선택지 응답의 민감 속성 표시.
  - 프로필 화면의 동의 체크박스와 안내문, 비회원 보관 값 형식 변경.
  - API 명세, `DEC-PROFILE`, `REQ-PROFILE-01`, 디자인 문서 F3.
- 제외:
  - 개인정보 처리방침 페이지(#111). 안내문의 문구는 #111이 처리방침에 옮겨 쓴다.
  - 동의 이력(언제 동의·철회했는지의 기록). 완료 조건은 현재 동의 시각만 요구한다.
  - 새 혜택 알림 대상 선정. 아직 구현되지 않았고, 구현되면 저장된 속성을 그대로 읽는다.
  - 가구 속성. 민감정보에 해당하는 값이 없다.

## 확인된 사실과 제약

- 회원 저장(`ProfileService.put`)과 비회원 조건 비교(`MatchService.matchWith`·`listWith`)가 같은 `ProfileRequest`를 받고 같은 `ProfileValidator.validate`로 검사한다. 검증기는 항목 오류를 모두 모아 `InvalidInputException`으로 한 번에 거절하므로, 거절되면 아무것도 저장되지 않는다.
- 대상 4개 속성의 지원조건 코드: `PROSPECTIVE_PARENT`=`JA0301`, `PREGNANT`=`JA0302`, `DISABLED`=`JA0328`, `PATIENT`=`JA0330`(`LifeAttribute`).
- 속성은 `user_profile_attribute`에 코드로 저장되고, 그룹에 코드가 없으면 미입력이다(결정 0022). 4개를 지운 뒤 생애 그룹에 남는 코드가 없으면 그 사용자의 생애 속성은 미입력이 된다. `NONE`(`JA0322`)은 다른 값과 함께 저장될 수 없으므로 지운 뒤 `NONE`만 남는 경우는 없다.
- 계정 → `user_profile` → `user_profile_attribute`가 `ON DELETE CASCADE`로 이어져 있다(V9). `user_profile`의 컬럼은 탈퇴할 때 행과 함께 지워진다.
- 스키마는 Flyway가 만들고 Hibernate는 검증만 한다. 다음 마이그레이션 번호는 V15다(#109의 V13, #107의 V14가 있다).
- 비회원 프로필은 `frontend/src/lib/profile/guestProfile.ts`가 sessionStorage에 `ProfileRequest` 그대로 두고(결정 0031), `matchRoute.ts`·`benefitDetailLoader.ts`가 읽어 조건 비교 요청 본문으로 보낸다. `isProfileRequest`가 형식이 맞지 않는 값을 미입력으로 본다.
- `profileForm.isEmptyProfile`은 `ProfileRequest`의 모든 값이 `null`인지로 판단한다. boolean 필드가 생기면 그대로는 항상 거짓이다.
- `AttributeChips`는 고른 값을 자기 상태로만 갖는다. 동의 체크박스를 보이고 숨기려면 페이지가 생애 속성 선택을 알아야 한다.
- 테스트 MySQL은 컨테이너 하나를 모든 컨텍스트가 함께 쓴다(결정 0033). 마이그레이션 데이터 처리를 확인한 테스트는 아직 없다.
- 2026-11-03 사용자 결정: 세 안(별도 동의 / 속성 제거 / 동의 없이 운영) 중 별도 동의를 골랐다.

## 설계 결정

1. **동의는 요청마다 `sensitiveAttributeConsent: true`로 보낸다.** 2026-11-03 사용자 결정.
   - 4개 속성 중 하나라도 있는데 이 값이 `true`가 아니면(`false`, `null`, 빠짐) 항목 오류다. 저장된 동의 기록이 있어도 마찬가지다.
   - 서버에 저장된 상태와 상관없이 회원·비회원 검증이 같다. 화면은 GET 응답의 동의 시각으로 체크를 미리 채운다.
   - 4개 속성이 없으면 이 값은 보지 않는다. `true`를 보내도 동의 기록을 만들지 않는다.
   - 대안: 동의 기록이 있으면 생략 허용. 회원과 비회원의 검증이 갈리고, 검증기가 저장소를 읽어야 한다.
2. **오류의 필드 이름은 `sensitiveAttributeConsent`다.** 2026-11-03 사용자 결정.
   - 값은 받은 값(`"false"`)이고, 없었으면 `null`이다(`InputError` 규칙).
   - 이유: "장애인·질병/질환자·임산부·예비부모/난임을 고르려면 건강 정보 처리에 동의해야 합니다."
   - 화면은 생애·직업·건강 카드의 동의 체크박스 아래에 붙인다.
3. **동의 시각은 `user_profile.sensitive_attribute_consented_at`에 두고, 처음 동의한 시각을 유지한다.** 2026-11-03 사용자 결정.
   - 4개 속성이 있는 저장: 기록이 없으면 지금 시각을 넣고, 있으면 그대로 둔다.
   - 4개 속성이 없는 저장: `null`로 비운다(철회).
   - 철회 뒤 다시 동의하면 새 시각이다.
   - 탈퇴하면 `user_profile` 행과 함께 지워지므로 `AccountService.withdraw`와 FK를 바꾸지 않는다.
   - 대안: 별도 동의 테이블. 이력을 남기지 않으므로 컬럼 하나로 충분하다.
   - 대안: 저장할 때마다 갱신. "언제 동의했는지"가 마지막 저장 시각과 같아져 `updatedAt`과 구분되지 않는다.
4. **민감 속성 여부는 `LifeAttribute`가 갖고, 선택지 API의 `AttributeOption.sensitive`로 내보낸다.**
   - 서버와 화면이 같은 기준을 쓴다. 화면에 4개 값을 따로 적지 않는다.
   - `ProfileAttribute`에 `default boolean sensitive()`(`false`)를 두고 `LifeAttribute`만 생성자 인자로 덮는다. 가구 속성은 모두 `false`다.
5. **기존 4개 속성은 V15에서 조건 없이 지운다.** 지금까지 동의 기록이 하나도 없으므로 완료 조건("동의 기록 없이 저장된 4개 속성이 남아 있지 않다")과 같다.
6. **안내문의 보유 기간은 회원 "탈퇴하거나 이 항목을 모두 빼고 저장할 때까지", 비회원 "서버에 저장하지 않음"이다.** 2026-11-03 사용자 결정. #111의 처리방침도 같은 표현을 쓴다.
7. **비회원 보관 값에 `sensitiveAttributeConsent`가 없으면 형식이 맞지 않는 값으로 보고 미입력으로 다룬다.**
   - 배포 전에 탭에 남은 값은 동의 없이 4개 속성을 담았을 수 있다. 그대로 보내면 조건 비교가 400이 된다.
   - 다시 입력하게 하는 것이 안전한 기본값이다. 값은 그 탭에만 있어서 영향이 작다.

## 단계별 구현 계획

1. **마이그레이션** `V15__add_user_profile_sensitive_attribute_consent.sql`
   ```sql
   -- 건강 관련 생애 속성의 별도 동의(#110, DEC-PROFILE, 개인정보 보호법 제23조). 처음 동의한 시각이며 NULL이면 동의 기록이 없다.
   -- 4개 속성을 모두 빼고 저장하면 비우고, 탈퇴하면 행과 함께 지워진다.
   ALTER TABLE user_profile
       ADD COLUMN sensitive_attribute_consented_at DATETIME(6) NULL AFTER sigungu_code;

   -- 동의 기록 없이 저장된 장애인(JA0328)·질병/질환자(JA0330)·임산부(JA0302)·예비부모/난임(JA0301)을 지운다.
   DELETE FROM user_profile_attribute WHERE condition_code IN ('JA0301', 'JA0302', 'JA0328', 'JA0330');
   ```
2. **도메인**
   - `ProfileAttribute`: `default boolean sensitive() { return false; }`. 주석: 건강에 관한 민감정보라 별도 동의가 필요한지.
   - `LifeAttribute`: 생성자에 `boolean sensitive`를 더하고 `PROSPECTIVE_PARENT`·`PREGNANT`·`DISABLED`·`PATIENT`만 `true`다. `sensitive()`를 덮는다. 클래스 주석에 #110·`DEC-PROFILE`을 더한다.
   - `UserProfile`
     - `private LocalDateTime sensitiveAttributeConsentedAt;`
     - `replace`: 바뀐 생애 속성에 `sensitive()`가 하나라도 있으면 기록이 없을 때만 `now`를 넣고, 없으면 `null`로 둔다(설계 3). 주석: 동의 여부는 검증기가 확인했다.
   - `ProfileValues`는 바꾸지 않는다. 동의는 검증에서 끝나고, 저장할 시각은 속성으로 정해진다.
3. **DTO**
   - `ProfileRequest`: `Boolean sensitiveAttributeConsent`를 마지막에 더한다. 설명: 장애인·질병/질환자·임산부·예비부모/난임 중 하나라도 보내면 `true`여야 한다. 요청마다 보낸다. 그 밖에는 보지 않는다. `empty()`도 고친다.
   - `ProfileResponse`: `OffsetDateTime sensitiveAttributeConsentedAt`를 `updatedAt` 앞에 더한다. 설명: 건강 관련 속성 처리에 처음 동의한 시각. 동의 기록이 없으면 `null`. `empty()`도 고친다.
   - `ProfileOptionsResponse.AttributeOption`: `boolean sensitive`를 더한다. 설명: `true`면 고를 때 `sensitiveAttributeConsent`가 필요하다. `of`가 `attribute.sensitive()`를 넣는다.
4. **검증** `ProfileValidator.validate`
   - `checkAttributes` 뒤에 `checkSensitiveConsent(request.lifeAttributes(), request.sensitiveAttributeConsent(), errors)`를 더한다.
   - 허용된 값 중 `LifeAttribute.valueOf(value).sensitive()`가 있고 `!Boolean.TRUE.equals(consent)`이면 설계 2의 오류를 더한다. 지원하지 않는 값은 `checkAttributes`가 이미 거절하므로 건너뛴다.
5. **서비스** `ProfileService.toResponse`: `ApiTimes.toOffset(profile.getSensitiveAttributeConsentedAt())`를 넣는다. `toOffset`은 `null`을 `null`로 돌려준다.
6. **API 명세** `OpenApiConfig.DESCRIPTION`의 프로필 항목에 한 문장을 더한다: 장애인·질병/질환자·임산부·예비부모/난임은 건강에 관한 민감정보라, 고를 때마다 `sensitiveAttributeConsent: true`를 함께 보내야 하며(선택지의 `sensitive`), 없으면 항목 오류다. 회원은 처음 동의한 시각이 기록되고, 이 속성을 모두 빼고 저장하면 지워진다.
7. **프론트 타입** `src/lib/api/types.ts`
   - `ProfileRequest`에 `sensitiveAttributeConsent: boolean`.
   - `ProfileResponse`에 `sensitiveAttributeConsentedAt: string | null`.
   - `AttributeOption`에 `sensitive: boolean`.
8. **프론트 폼** `src/lib/profile/profileForm.ts`
   - `profileFromForm`: `sensitiveAttributeConsent: form.get("sensitiveAttributeConsent") === "true"`. 체크박스가 숨겨져 폼에 없으면 `false`다.
   - `requestFromResponse`: `sensitiveAttributeConsent: profile.sensitiveAttributeConsentedAt !== null`.
   - `isEmptyProfile`: `sensitiveAttributeConsent`를 빼고 나머지 값이 모두 `null`인지 본다.
9. **비회원 보관** `src/lib/profile/guestProfile.ts`의 `isProfileRequest`: `typeof record.sensitiveAttributeConsent === "boolean"`을 더한다(설계 7). 파일 주석에 이유를 적는다.
10. **화면**
    - `AttributeChips`: 선택이 바뀔 때 부르는 `onChange?: (selected: string[]) => void`를 더한다.
    - `ProfilePage`: 생애·직업·건강 카드가 고른 값을 상태로 갖고, 고른 값 중 `options.lifeAttributes`의 `sensitive`가 하나라도 있으면 카드 안에 동의 블록을 보인다. 모두 해제하면 블록이 사라진다.
      - 체크박스: `name="sensitiveAttributeConsent" value="true"`, `defaultChecked={profile?.sensitiveAttributeConsent ?? false}`. 라벨은 "건강 관련 정보 처리에 동의해요(선택)".
      - 안내문(회원·비회원 공통 항목, 보유 기간만 다르다):
        - 처리 목적: 혜택의 대상 조건과 비교해요. 회원은 새 혜택 알림 대상을 고를 때도 써요.
        - 항목: 고른 장애인·질병/질환자·임산부·예비부모/난임.
        - 보유 기간: 회원은 탈퇴하거나 이 항목을 모두 빼고 저장할 때까지. 비회원은 서버에 저장하지 않아요.
        - 거부할 권리와 불이익: 동의하지 않아도 돼요. 그러면 이 항목을 고를 수 없고, 이 조건이 있는 혜택은 비교 결과에서 확인 필요로 보여요. 회원은 새 혜택 알림에서 빠질 수 있어요.
      - 오류: 카드의 `errors`에 `errors.sensitiveAttributeConsent`를 더하고 체크박스에 `aria-invalid`·`aria-describedby`를 붙인다.
    - `ProfilePage.module.css`: 동의 블록 스타일(기존 `hint`·`card` 토큰을 쓴다).
11. **테스트**
    - `ProfileApiTest`
      - 4개 값 각각(`@ParameterizedTest`)을 동의 없이, `false`로, `null`로 보내면 400 `INVALID_INPUT`이고 오류 필드가 `sensitiveAttributeConsent`다. 다른 값과 함께 보낸 저장도 처음 저장이면 프로필 행이 생기지 않고, 이미 있던 프로필이면 값이 그대로다.
      - 동의와 함께 저장하면 200이고 속성이 저장되며, 응답·DB의 동의 시각이 `clock` 시각이다.
      - 시계를 옮겨 동의와 함께 다시 저장하면 동의 시각이 처음 값이다. `updatedAt`은 바뀐다.
      - 4개 속성을 모두 빼고 저장하면 응답·DB의 동의 시각이 `null`이고 4개 코드가 남지 않는다. 다시 동의하면 새 시각이다.
      - 4개가 아닌 속성만(`EMPLOYEE`, `["NONE"]`, 미입력) 동의 없이 저장하면 200이고 동의 시각이 `null`이다. `true`를 보내도 `null`이다.
      - 선택지의 `sensitive`가 `true`인 값이 정확히 4개다. 가구 속성은 모두 `false`다.
      - 기존 테스트 중 `PREGNANT`를 저장하는 것(`저장한_값이_조회되고_수정하면_바뀐_값이_조회된다`, `다른_사용자가_저장한_값은_보이지_않는다`)에 동의를 더하고, 응답 필드 비교에 `sensitiveAttributeConsentedAt`을 넣는다.
    - `PublicMatchApiTest`
      - 목록·상세 모두 동의 없이 4개 속성을 보내면 400이고 오류 필드가 `sensitiveAttributeConsent`다.
      - 기존 판정 테스트(`PREGNANT`를 보내는 것)에 동의를 더해 판정이 지금과 같은지 확인한다.
    - `WithdrawalApiTest`: 동의와 함께 저장한 뒤 탈퇴하면 `user_profile` 행(동의 시각 포함)이 남지 않는다.
    - `profile.SensitiveAttributeMigrationTest`(새 파일)
      - 공용 컨테이너에 별도 스키마를 만들어 Flyway로 V14까지 올린 뒤, 4개 코드와 다른 코드를 함께 가진 프로필과 4개만 가진 프로필을 넣고 V15를 적용한다.
      - 4개 코드가 남지 않고, 다른 코드는 남고, 동의 시각이 모두 `NULL`이다. 스키마는 테스트 끝에 지운다.
      - 스키마 생성 권한이 테스트 계정에 없으면 컨테이너의 root 계정으로 접속한다. 구현 때 확인한다.
    - `ApiDocsTest`의 프로필 테스트: `ProfileRequest` 속성에 `sensitiveAttributeConsent`, `ProfileResponse`에 `sensitiveAttributeConsentedAt`, `AttributeOption`에 `sensitive`를 더한다.
12. **문서**
    - `docs/product/development.md`의 `DEC-PROFILE` 행: 장애인·질병/질환자·임산부·예비부모/난임은 건강에 관한 민감정보(개인정보 보호법 제23조)라 고를 때만 별도 동의를 받는다. 회원은 처음 동의한 시각을 기록하고, 모두 빼고 저장하면 철회로 보고 지운다(2026-11-03, #110). 근거 칸에 세 안 중 별도 동의를 고른 이유(속성을 빼면 조건 비교·새 혜택 알림 대상이 준다)를 더한다.
    - `docs/product/requirements.md`의 `REQ-PROFILE-01`: 위 동작과 400 항목 오류를 요구사항으로 더한다.
    - `docs/design/README.md` F3: 생애·직업·건강 카드에 동의 블록이 붙는다는 것을 더한다.

## 완료 조건 매핑

- [ ] 4개 속성 중 하나를 고르면 동의 체크박스가 나타나고, 모두 해제하면 사라진다. → 설계 4, 10단계 `ProfilePage`. 로컬 화면에서 확인한다(프론트 테스트 도구가 없다).
- [ ] 동의 없이 4개 속성을 담은 프로필 저장·비회원 조건 비교 요청은 400 항목별 오류이고, 아무것도 저장되지 않는다. → 설계 1·2, 4단계. `ProfileApiTest`, `PublicMatchApiTest`.
- [ ] 동의하고 저장한 4개 속성은 지금처럼 조건 비교에 쓰이고, 동의 시각이 기록된다. → 설계 3. `ProfileApiTest`, `PublicMatchApiTest`의 기존 판정 테스트.
- [ ] 4개 속성을 모두 빼고 저장하면 속성과 동의 기록이 남지 않는다. 탈퇴하면 동의 기록도 삭제된다. → 설계 3. `ProfileApiTest`의 철회, `WithdrawalApiTest`.
- [ ] 4개 속성이 아닌 속성만 고르면 동의 없이 저장·비교된다. → 설계 1. `ProfileApiTest`, `PublicMatchApiTest`의 기존 테스트.
- [ ] 배포 뒤 동의 기록 없이 저장된 4개 속성이 남아 있지 않다. → 설계 5, 1단계. `SensitiveAttributeMigrationTest`.

## 검증 명령

- `backend/`에서 `./gradlew test`
- `frontend/`에서 `npm run lint`, `npm run typecheck`, `VITE_API_BASE_URL=http://localhost:8080 npm run build`
- 로컬 확인: 백엔드(`SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`)와 프론트(`npm run dev`)를 띄우고 다음을 확인한다.
  - 비회원으로 `/profile`에서 임산부를 고르면 동의 블록이 나타나고, 해제하면 사라진다. 동의 없이 제출하면 체크박스 아래에 오류가 보인다. 동의하고 제출하면 조건 비교 목록으로 간다.
  - 회원으로 같은 흐름을 저장한 뒤 다시 열면 체크가 채워져 있다. 4개 속성을 모두 빼고 저장한 뒤 `GET /api/me/profile`의 `sensitiveAttributeConsentedAt`이 `null`이다.
  - 아래 요청이 400이고 `errors[0].field`가 `sensitiveAttributeConsent`다.
    ```sh
    curl -si -X POST 'localhost:8080/api/matches?page=1&size=1' -H 'Content-Type: application/json' \
      -d '{"lifeAttributes":["DISABLED"]}'
    ```

## 결정 필요

없음
