status: in-progress

# 구현 계획 — #56 로그인한 사용자의 관심 혜택 저장·해제·목록 조회 제공

브랜치: `feat/56-saved-benefits` (develop `65a761b` 기준)

## 목표 및 범위

- 목표: 로그인한 사용자가 혜택을 관심 목록에 저장·해제하고, 혜택의 현재 정보로 자기 관심 목록을 조회한다(`REQ-SAVE-01` P0).
- 포함:
  - `PUT /api/me/bookmarks/{serviceId}`(저장, 204), `DELETE /api/me/bookmarks/{serviceId}`(해제, 204), `GET /api/me/bookmarks`(목록, 200).
  - `bookmark` 테이블(V10). `user_id`는 `ON DELETE CASCADE`, `benefit_id`는 RESTRICT FK다.
  - 새 도메인 패키지 `kr.govbenefit.bookmark`.
  - 결정 문서 0023(북마크의 혜택 참조), API 명세, `ApiDocsTest`, 탈퇴 테스트의 삭제 범위.
- 제외(이슈 본문):
  - 관심 목록 안에서의 검색·필터.
  - 조건 비교 결과 표시(`REQ-MATCH-01` 이슈).
  - 종료된 혜택 표시(`REQ-SAVE-01` P1).
  - 알림 연결(3차).
  - 검색·상세 응답의 저장 여부 표시. 공개 API에 선택적 인증이 필요해 인증 경계(결정 0018)가 바뀐다.

## 확인된 사실과 제약

- 수집은 `benefit` 행을 지우지 않는다. 목록에서 빠지면 `last_seen_at`만 멈춘다(`Benefit.confirm`, `CollectionPageWriter`). 그래서 북마크가 가리키는 혜택 행은 항상 남는다.
- `benefit.service_id`는 UNIQUE이고, API에서 혜택을 가리키는 식별자는 서비스 ID다(`DEC-SEARCH`). `benefit_detail`은 `benefit_id`로 `benefit`을 참조한다(V1).
- 인증 경계: `SecurityConfig`가 `PUBLIC_PATHS` 밖의 요청을 모두 인증하고, `/api/me/**`는 공개 경로가 아니다(결정 0018). 사용자 ID는 `@CurrentUserId`로만 받는다.
- 남은 액세스 토큰: 액세스 토큰 검증은 DB를 보지 않는다(결정 0014·0018). 그래서 개인 API는 서비스가 계정 존재를 확인해 없으면 401 `UNAUTHENTICATED`를 낸다(`AccountService.me`, `ProfileService.get`·`put`). `MeController` 탈퇴 설명에 "남은 토큰으로 보낸 개인 API 요청은 401"이라고 적혀 있다.
- 탈퇴는 `user_account` 행을 `findByIdForUpdate`로 잠그고 지운다. 사용자 데이터는 FK `ON DELETE CASCADE`로 함께 지워진다(#51 설계 1).
- 저장소 메서드에서 난 예외는 트랜잭션을 rollback-only로 만들어 서비스 안에서 잡아 처리할 수 없다(#51 확인된 사실).
- MySQL은 compose와 Testcontainers 모두 8.4다. `INSERT ... ON DUPLICATE KEY UPDATE`의 `VALUES()` 함수는 8.0.20부터 폐기 예정이므로 행 별칭(`AS new`)을 쓴다.
- `INSERT IGNORE`는 FK 위반을 경고로 바꿔 삼킨다.
- 테스트 정리: 혜택 행을 전부 지우는 테스트가 있다. `CollectionSchedulerTest`, `CollectionServiceTest`, `CollectionFaultToleranceTest`, `BenefitNormalizationTest`, `BenefitDetailApiTest`, `BenefitSearchApiTest`가 `DELETE FROM benefit`을 실행한다. Testcontainers 컨테이너는 Spring 컨텍스트마다 하나라 같은 컨텍스트를 쓰는 테스트 클래스끼리 DB를 공유한다. `bookmark` 행이 남아 있으면 RESTRICT FK 때문에 이 삭제가 실패한다.
- `Gov24StubServer`에는 서비스를 목록에서 빼는 `removeService`와 실패 응답을 넣는 `override`가 있다.

## 설계 결정

1. **북마크는 `benefit_id`로 혜택을 참조하고, FK는 RESTRICT다.** 2026-10-20 사용자 결정. → [결정 0023](../decisions/0023-bookmark-benefit-reference.md)
   - `benefit_detail`과 같은 방식이고, DB가 혜택의 존재를 보장한다. 북마크가 달린 혜택 행은 지울 수 없어, "북마크를 임의로 삭제하지 않는다"(`REQ-SAVE-01`)를 DB가 지킨다.
   - 대안: `service_id` 문자열, FK 없음. API 식별자와 같고 혜택 행을 다시 만들어도 연결이 유지되지만, 존재 검사를 애플리케이션만 한다.
   - 대안: `service_id` → `benefit.service_id` FK. 저장소에서 처음 쓰는 방식이고 VARCHAR FK다.
   - 영향: 이후 혜택 비노출·종료(`REQ-DATA-01` P1)는 행 삭제가 아니라 상태로 다뤄야 한다. 그래서 결정 문서로 올린다.
2. **PK는 `(user_id, benefit_id)` 복합 키이고, 대리키를 두지 않는다.**
   - 유니크 인덱스가 하나라 중복 저장을 DB가 막는다. auto_increment 값을 소비하지 않고, 동시 `ON DUPLICATE KEY UPDATE`의 데드락 가능성도 줄어든다. 데드락은 동시성 테스트로 확인한다.
3. **저장은 계정이 있는지 확인한 뒤 `INSERT ... ON DUPLICATE KEY UPDATE`로 한다. 계정 행은 잠그지 않는다.** 2026-10-20 사용자 결정.
   - 한 문장이라 원자적이고 잠금이 짧다. 동시 저장이나 재전송이 와도 행은 하나다.
   - 계정이 없으면 INSERT 전에 401 `UNAUTHENTICATED`를 낸다. 이 확인이 없으면 탈퇴 뒤 남은 토큰의 저장이 FK 위반으로 500이 된다.
   - 대안: 계정 행 `FOR UPDATE` 잠금 후 확인과 INSERT(`ProfileService.put`과 같은 방식). 탈퇴와의 경합까지 막지만 같은 사용자의 저장이 차례로 처리된다.
   - 대안: JPA `save`를 하고 중복 키 예외를 잡음. rollback-only 문제로 처리 위치가 복잡해진다.
   - `INSERT IGNORE`는 쓰지 않는다. FK 위반까지 삼켜 실패가 성공처럼 보인다.
   - 한계: 계정 확인과 INSERT 사이에 탈퇴가 커밋되면 FK 위반으로 500이 된다. 데이터는 남지 않는다. #51 설계 5의 로그인·재발급 한계와 같은 종류다.
4. **다시 저장하면 `bookmarked_at`을 그 시각으로 갱신한다.** 2026-10-20 사용자 결정.
   - `ON DUPLICATE KEY UPDATE bookmarked_at = new.bookmarked_at`. 다시 저장한 혜택이 목록 맨 위로 올라간다.
   - 멱등의 범위: 같은 요청을 다시 보내도 사용자·혜택 조합은 한 건이다(`REQ-SAVE-01`). `bookmarked_at`은 마지막 저장 시각이라 바뀔 수 있다.
5. **API는 `PUT`·`DELETE /api/me/bookmarks/{serviceId}`와 `GET /api/me/bookmarks`다.** 2026-10-20 사용자 결정.
   - PUT과 DELETE는 의미상 멱등이고, 본문이 없어 다른 사용자 ID를 넣을 자리가 없다.
   - 저장과 해제는 처음이든 다시든 204다.
   - 대안: `POST` + 본문 `{serviceId}`. 멱등이 아니라는 기대와 어긋나고 201과 200을 나눠야 한다.
6. **수집되지 않은 서비스 ID: 저장은 404 `NOT_FOUND`, 해제는 204다.** 2026-10-20 사용자 결정.
   - 저장 거절은 상세 조회와 같은 코드라 새 오류 코드가 필요 없다.
   - 해제는 "미저장 상태로 끝난다"는 결과가 같으므로 오류를 내지 않는다.
7. **개인 API 세 개 모두 계정이 없으면 401 `UNAUTHENTICATED`다.** 남은 토큰으로 보낸 개인 API 요청은 401이라는 기존 약속(`MeController`)을 따른다. 해제와 목록도 계정 존재를 확인한다.
8. **목록은 저장 최신순이고, 검색과 같은 번호·크기 페이지를 쓴다.** 2026-10-20 사용자 결정.
   - 정렬: `bookmarked_at DESC, benefit_id DESC`.
   - 페이지: 기본 20건, 최대 100건이다. 범위를 벗어난 번호·크기는 400 `INVALID_INPUT`, 마지막 쪽을 넘은 번호는 빈 목록이다(`DEC-SEARCH`와 같음).
   - 저장 개수 상한은 두지 않는다.
9. **목록 항목은 `{bookmarkedAt, benefit}`이고, `benefit`은 `BenefitSummary`를 그대로 쓴다.** 2026-10-20 사용자 결정(`bookmarkedAt` 포함).
   - 혜택의 현재 행에서 매번 만들어 다시 수집된 내용이 반영된다. 저장 시점의 값을 복사하지 않는다.
   - 목록에서 사라진 혜택도 숨기지 않는다(`DEC-SEARCH`). `benefit.lastSeenAt`이 멈춘 값으로 나온다.
   - 대안: `BenefitSummary` 필드를 펼쳐 `bookmarkedAt`과 나란히 둠. 응답이 평평해지지만 필드와 명세 설명을 중복해 유지해야 한다.
10. **조회는 북마크 한 쪽을 읽고 그 `benefit_id`들로 혜택을 한 번에 읽는다.**
    - `BookmarkRepository.findByUserId(userId, Pageable)` 다음 `BenefitRepository.findAllById(ids)`. 한 읽기 트랜잭션이라 같은 스냅샷을 본다. RESTRICT FK 때문에 모든 기록에 혜택 행이 있다.
    - 대안: 관련 없는 엔티티끼리 JPQL `join ... on`. 쿼리가 하나 줄지만 결과 매핑이 `Object[]`가 되고, 저장소에서 처음 쓰는 방식이다.
11. **코드·API 이름은 북마크(`bookmark`)다. 패키지는 `kr.govbenefit.bookmark`다.** 2026-10-20 사용자 결정.
    - URL·테이블·클래스·필드(`bookmarkedAt`)를 모두 `bookmark`로 맞춘다. 제품 문서의 '관심 목록'(`REQ-SAVE-01`)은 이 기능을 가리키며, 제품 문서와 이슈 문구는 바꾸지 않는다.
    - `bookmark`는 `benefit` 저장소와 `account` 저장소를 읽기만 한다. `benefit`은 관심 목록을 모른다.
    - 엔티티는 연관(`@ManyToOne`) 없이 `Long benefitId`를 갖는다. `UserProfile`이 `userId`를 갖는 방식과 같다.
12. **탈퇴할 때의 삭제는 `user_id` FK `ON DELETE CASCADE`에 맡긴다.** #51 설계 1을 그대로 따르므로 `AccountService`는 바꾸지 않는다.

## 단계별 구현 계획

1. **마이그레이션** `V10__create_bookmark.sql`
   ```sql
   -- 북마크(#56, REQ-SAVE-01). 사용자·혜택 조합당 한 행이다. bookmarked_at은 마지막으로 저장한 시각이다.
   -- benefit_id는 RESTRICT라 북마크가 달린 혜택 행은 지울 수 없다(결정 0023).
   CREATE TABLE bookmark (
       user_id       BIGINT      NOT NULL,
       benefit_id    BIGINT      NOT NULL,
       bookmarked_at DATETIME(6) NOT NULL,
       PRIMARY KEY (user_id, benefit_id),
       INDEX idx_bookmark_user_bookmarked (user_id, bookmarked_at, benefit_id),
       CONSTRAINT fk_bookmark_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
       CONSTRAINT fk_bookmark_benefit FOREIGN KEY (benefit_id) REFERENCES benefit (id)
   );
   ```
   - `benefit_id` FK에는 InnoDB가 인덱스를 따로 만든다.
2. **엔티티** `bookmark.domain.Bookmark`
   - 복합 키는 `@IdClass(Bookmark.Key.class)`로 하고 `Key(Long userId, Long benefitId)`를 둔다.
   - 필드는 `userId`, `benefitId`, `bookmarkedAt`. 쓰기는 저장소의 네이티브 쿼리로만 하므로 생성 메서드를 두지 않는다.
3. **저장소** `bookmark.repository.BookmarkRepository extends JpaRepository<Bookmark, Bookmark.Key>`
   - `@Modifying @Query(nativeQuery = true) int upsert(Long userId, Long benefitId, LocalDateTime bookmarkedAt)`
     ```sql
     INSERT INTO bookmark (user_id, benefit_id, bookmarked_at) VALUES (:userId, :benefitId, :bookmarkedAt) AS new
     ON DUPLICATE KEY UPDATE bookmarked_at = new.bookmarked_at
     ```
   - `@Modifying @Query("delete from Bookmark b where b.userId = :userId and b.benefitId = :benefitId") int deleteByUserIdAndBenefitId(...)`. 엔티티를 읽지 않고 한 문장으로 지운다.
   - `Page<Bookmark> findByUserId(Long userId, Pageable pageable)`.
4. **DTO** `bookmark.dto`
   - `BookmarkListRequest(Integer page, Integer size)`: 검증과 기본값은 `BenefitSearchRequest`와 같다. 최대·기본 크기는 `BenefitSearchRequest.MAX_SIZE`·`DEFAULT_SIZE`를 쓰고, 메시지 문자열은 이 클래스에 둔다(`BenefitSearchRequest`의 상수가 package-private이고, 공개로 바꾸는 것은 범위 밖이다).
   - `BookmarkItem(OffsetDateTime bookmarkedAt, BenefitSummary benefit)`: `bookmarkedAt`은 `ApiTimes.toOffset`으로 바꾼다.
   - `BookmarkListResponse(long totalCount, int page, int size, List<BookmarkItem> items)`: 필드 설명은 `BenefitSearchResponse`와 같게 쓴다.
5. **서비스** `bookmark.service.BookmarkService`
   - 공통: `userAccountRepository.existsById(userId)`가 거짓이면 `BusinessException(UNAUTHENTICATED)`.
   - `@Transactional void save(Long userId, String serviceId)`: 계정 확인 → `benefitRepository.findByServiceId`가 없으면 `BusinessException(NOT_FOUND, "혜택이 없습니다. serviceId=...")` → `interestRepository.upsert(userId, benefit.getId(), LocalDateTime.now(clock))`.
   - `@Transactional void remove(Long userId, String serviceId)`: 계정 확인 → `findByServiceId(serviceId).ifPresent(b -> interestRepository.deleteByUserIdAndBenefitId(userId, b.getId()))`.
   - `@Transactional(readOnly = true) BookmarkListResponse list(Long userId, int page, int size)`
     - 계정 확인.
     - 시작 위치가 `int`를 넘으면 `count`만 하고 빈 목록을 돌려준다(`BenefitQueryService.search`와 같은 처리).
     - `findByUserId(userId, PageRequest.of(page - 1, size, Sort.by(desc("bookmarkedAt"), desc("benefitId"))))` → `benefitRepository.findAllById` → 쪽 순서대로 `BookmarkItem`을 만든다.
   - 클래스 주석: `REQ-SAVE-01`, 설계 3의 한계.
6. **컨트롤러** `bookmark.controller.BookmarkController`, `@RequestMapping("/api/me/bookmarks")`, `@Tag(name = "북마크")`
   - `@PutMapping("/{serviceId}")` → 204.
   - `@DeleteMapping("/{serviceId}")` → 204.
   - `@GetMapping` with `@ParameterObject @Valid BookmarkListRequest` → 200.
   - 모두 `security = BEARER_AUTH`를 붙인다.
   - `@ApiResponse`는 기존 표기를 따른다.
     - 저장: 204, 404 `NOT_FOUND`, 401 `UNAUTHENTICATED`.
     - 해제: 204, 401.
     - 목록: 200, 400 `INVALID_INPUT`, 401.
   - `serviceId` 파라미터 설명은 `BenefitController.detail`과 같다.
7. **API 명세** `OpenApiConfig.DESCRIPTION`
   - 첫 줄의 API 목록에 북마크를 넣는다.
   - 항목을 하나 더한다: 북마크는 서비스 ID로 저장(`PUT`)·해제(`DELETE`)하고 둘 다 다시 보내도 결과가 같다. 목록은 마지막으로 저장한 순서이고 혜택의 현재 정보를 보여준다.
   - 탈퇴 항목의 삭제 대상에 관심 목록을 넣는다(`MeController` 탈퇴 설명도 같이 고친다).
8. **결정 문서** `docs/decisions/0023-bookmark-benefit-reference.md`(템플릿)와 `docs/decisions/README.md` 목록 표
   - 결정: 북마크는 `benefit_id` RESTRICT FK로 혜택을 참조한다. 북마크가 달린 혜택 행은 지우지 않는다.
   - 영향: 혜택 비노출·종료(`REQ-DATA-01` P1, `REQ-SAVE-01` P1)와 알림(3차)은 혜택 행 삭제가 아니라 상태 컬럼으로 다룬다. 혜택 행을 지워야 하면 이 결정을 대체한다.
9. **테스트**
   - 기존 테스트 정리 순서: 위 "확인된 사실"의 혜택 삭제 테스트 6개에서 `benefit` 앞에 `bookmark`를 지운다.
   - `WithdrawalApiTest`: `USER_TABLES`에 `bookmark`를 넣고, 탈퇴 전 데이터 준비에서 북마크를 하나 저장한다. 혜택 행은 `BenefitRepository`로 만든다.
   - `bookmark.controller.BookmarkApiTest`(`@SpringBootTest`, MockMvc, Testcontainers, 고정 시계는 `MutableClock`). 계정은 `SignUpSteps`, 혜택은 `Benefit.create`로 만든다.
     - 저장하면 204이고 목록에 나타난다. 해제하면 204이고 목록에서 사라진다.
     - 같은 혜택을 두 번 저장해도 목록에 한 건이고, `bookmarkedAt`이 두 번째 시각이며 맨 위로 온다.
     - 저장하지 않은 혜택, 이미 해제한 혜택, 수집되지 않은 서비스 ID를 해제해도 204이고 목록이 그대로다.
     - 수집되지 않은 서비스 ID를 저장하면 404 `NOT_FOUND`이고 목록이 그대로다.
     - 저장한 뒤 stub의 혜택 이름을 바꿔 다시 수집하면 목록에 바뀐 이름이 보이고 `bookmarkedAt`은 그대로다. 구현 중 변경: `Benefit.confirm`을 직접 부르는 대신 실제 수집으로 확인한다.
     - 구현 중 추가: 액세스 토큰은 고정 시계 기준 15분 뒤 만료되므로, 시계를 하루 옮긴 수집 시나리오에서는 토큰을 다시 발급한다.
     - 저장한 뒤 `Gov24StubServer.removeService`로 목록에서 빼고 수집하거나, `override`로 수집을 실패시키면 북마크가 남고 목록에 나온다. 앞의 경우는 `lastSeenAt`이 멈춘다.
     - 목록 정렬이 저장 최신순이고, 페이지 경계(크기 101과 쪽 0은 400, 마지막 쪽을 넘으면 빈 목록, `totalCount`)가 맞다.
     - A의 저장·해제가 B의 목록에 영향이 없다.
     - 요청에 `userId` 쿼리 파라미터를 넣어도 토큰 주인의 목록만 읽고 바꾼다.
     - 토큰 없이 보낸 저장·해제·목록은 401이다.
     - 탈퇴 뒤 남은 토큰으로 보낸 저장·해제·목록은 401이고, 저장 요청에 500이 나지 않는다.
     - 탈퇴하면 북마크가 0건이고, 같은 이메일로 다시 가입한 사용자의 목록은 비어 있다.
   - `bookmark.service.BookmarkConcurrencyTest`(`RefreshTokenConcurrencyTest`처럼 `CountDownLatch`): 같은 사용자가 같은 혜택을 여러 스레드에서 동시에 저장하면 모두 예외 없이 끝나고 행이 한 건이다.
   - `ApiDocsTest`
     - `/api/me/bookmarks/{serviceId}`의 `put`·`delete`와 `/api/me/bookmarks`의 `get`이 bearer 보안을 요구한다.
     - 응답 코드: 저장은 204·401·404·500, 해제는 204·401·500, 목록은 200·400·401·500이다.
     - 요청 파라미터에 `userId`가 없다.
   - 기존 테스트는 정리 순서 외에는 바꾸지 않고 통과한다.
10. **문서**
    - `docs/product/development.md`의 `DEC-AUTH` 행은 이미 "탈퇴하면 관심 목록을 즉시 삭제"를 담고 있어 바꾸지 않는다.

## 완료 조건 매핑

- [ ] 로그인한 사용자가 혜택을 저장하면 관심 목록에 나타나고, 해제하면 사라진다. → 설계 5·9. `BookmarkApiTest`의 저장·해제.
- [ ] 같은 혜택을 동시에 저장하거나 저장 요청을 다시 보내도 관심 목록에는 한 건만 남는다. → 설계 2·3. `BookmarkConcurrencyTest`, `BookmarkApiTest`의 두 번 저장.
- [ ] 저장하지 않았거나 이미 해제한 혜택을 해제해도 오류 없이 미저장 상태로 끝난다. → 설계 6. `BookmarkApiTest`의 해제 204.
- [ ] 수집되지 않은 서비스 ID를 저장하려 하면 거절되고 관심 목록이 바뀌지 않는다. → 설계 6. `BookmarkApiTest`의 404.
- [ ] 저장한 뒤 다시 수집되어 혜택 정보가 바뀌면 관심 목록에 바뀐 정보가 보인다. → 설계 9. `BookmarkApiTest`의 재수집.
- [ ] 저장한 혜택이 수집 목록에서 사라지거나 수집이 실패해도 관심 기록은 지워지지 않고 목록에 남는다. → 설계 1·9, 결정 0023. `BookmarkApiTest`의 `removeService`·`override` 수집.
- [ ] 사용자 A의 저장·해제가 사용자 B의 관심 목록에 영향을 주지 않고, 요청에 다른 사용자 ID를 넣어도 그 사용자의 관심 목록을 읽거나 바꿀 수 없다. → 결정 0018, 설계 5. `BookmarkApiTest`의 A·B, `userId` 파라미터, `ApiDocsTest`.
- [ ] 인증되지 않은 저장·해제·목록 요청은 거절된다. → 결정 0018, 설계 7. `BookmarkApiTest`의 토큰 없음·탈퇴 뒤 토큰.
- [ ] 탈퇴한 사용자의 관심 목록이 삭제되고, 같은 이메일로 다시 가입한 사용자의 관심 목록은 비어 있다. → 설계 12. `WithdrawalApiTest`의 `USER_TABLES`, `BookmarkApiTest`의 재가입.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인: `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤 가입·로그인으로 액세스 토큰을 받고, 수집된 서비스 ID(`$SID`)로 다음을 실행한다.
  ```sh
  curl -si -X PUT localhost:8080/api/me/bookmarks/$SID -H "Authorization: Bearer $TOKEN"
  curl -si -X PUT localhost:8080/api/me/bookmarks/$SID -H "Authorization: Bearer $TOKEN"
  curl -si localhost:8080/api/me/bookmarks -H "Authorization: Bearer $TOKEN"
  curl -si -X DELETE localhost:8080/api/me/bookmarks/$SID -H "Authorization: Bearer $TOKEN"
  curl -si -X DELETE localhost:8080/api/me/bookmarks/$SID -H "Authorization: Bearer $TOKEN"
  curl -si -X PUT localhost:8080/api/me/bookmarks/NO_SUCH_ID -H "Authorization: Bearer $TOKEN"
  ```
  다음을 확인한다.
  - 두 번의 저장이 모두 204이고, 목록이 한 건이다.
  - 두 번의 해제가 모두 204다.
  - 없는 ID 저장이 404다.

## 결정 필요

없음
