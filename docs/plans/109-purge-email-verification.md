status: done

# 구현 계획 — #109 이메일 인증 기록을 발송 24시간 뒤 파기

브랜치: `improve/109-purge-email-verification` (develop `2bb9cbd` 기준)

## 목표 및 범위

- 목표: 발송 후 24시간이 지난 이메일 인증 기록(`email_verification`)을 하루 한 번 지워, 가입하지 않은 사람과 탈퇴한 사람의 이메일이 기한 없이 남지 않게 한다.
- 포함:
  - 하루 한 번 도는 삭제 작업과 `created_at` 기준 삭제 쿼리.
  - `email_verification.created_at` 인덱스(V13).
  - 결정 0019를 대체하는 결정 0041과 코드·제품 문서의 참조 변경.
  - `DEC-AUTH`, `REQ-ACCOUNT-01`의 보유 기간 반영.
- 제외:
  - 비밀번호 재설정 토큰(`password_reset_token`). 계정을 `ON DELETE CASCADE`로 참조해 탈퇴하면 함께 지워지고, 이슈 범위가 아니다.
  - 개인정보 처리방침 페이지(#111).
  - 설정값끼리의 관계 검사(예: 유효기간이 발송 제한 기간보다 긴 설정을 기동할 때 거절). 지금 설정에서는 생기지 않는다.

## 확인된 사실과 제약

- 인증 기록이 쓰이는 곳(결정 0019, `EmailVerificationService`):
  - 인증번호 확인: 같은 이메일의 가장 최근 행. 발송 후 10분(`auth.email-verification.code-ttl`)까지만 확인할 수 있다.
  - 가입 티켓: `findByTicketHash`. 번호를 맞힌 뒤 30분(`ticket-ttl`)까지라 발송 후 최대 40분이다.
  - 발송 간격: 가장 최근 행의 `created_at`. 60초(`auth.mail.cooldown`).
  - 하루 횟수: `created_at > now - window`인 행의 수와 그중 가장 오래된 행. `window`는 24시간(`auth.mail.window`)이다.
- 따라서 `created_at <= now - window`인 행은 횟수와 가장 오래된 행 계산에서 이미 빠져 있고, 간격·인증번호·티켓에도 쓰이지 않는다. 지워도 응답이 바뀌지 않는다. 행이 없을 때의 응답(확인은 `VERIFICATION_CODE_EXPIRED`, 가입은 `EMAIL_NOT_VERIFIED`)은 만료와 같다.
- 탈퇴(`AccountService.withdraw`)는 계정 행만 지운다. `email_verification`은 FK가 없어 남는다.
- 기존 인덱스는 `idx_email_verification_email_created (email, created_at)`이다. `created_at`만으로 지우는 쿼리는 이 인덱스를 쓸 수 없어 테이블 전체를 훑는다. 다음 마이그레이션 번호는 V13이다.
- `V6__create_email_verification.sql` 주석에 "행을 지우지 않는다"가 있다. 적용된 마이그레이션을 고치면 Flyway 체크섬 검증이 실패하므로 그대로 둔다. V6·V7 주석의 "결정 0019"도 같다.
- 스케줄은 `CollectionConfig`의 `@EnableScheduling`으로 켜져 있다. 정기 작업은 `CollectionScheduler`(`cron`, `zone = "Asia/Seoul"`)와 `LoginAttemptLimiter.evictExpired`가 있다. 단일 인스턴스를 전제한다(결정 0039).
- 시각은 `Clock` bean으로 읽고(결정 0002), 테스트는 `FixedClockConfiguration`·`MutableClock`으로 옮긴다(결정 0033).
- 결정 대체 절차: 새 번호로 결정 전체를 옮기고, 기존 문서는 상태 줄만 바꾸며, 목록 표를 고친다. #106(0029 → 0040)과 #73에서 코드·제품 문서의 참조를 새 번호로 바꿨고, 다른 결정 문서와 플랜의 본문은 고치지 않았다.
- 0019를 가리키는 곳: 백엔드 `src/main`의 Java 29개 파일·`src/test` 6개 파일·`application.yaml`, 마이그레이션 V6·V7, 프론트 주석 4개 파일(`src/lib/api/auth.ts`, `src/pages/account/password/passwordRoutes.ts`, `ResetPasswordPage.tsx`, `src/pages/account/signup/SignupPage.tsx`), `docs/product/development.md`.

## 설계 결정

1. **보유 기간은 발송 제한 기간 `auth.mail.window`를 그대로 쓴다.** 이슈 결정(2026-11-03).
   - 삭제 기준은 `created_at <= now - window`다. 발송 제한의 창(`created_at > now - window`)과 정확히 맞물려, 창 밖으로 나간 행만 지운다.
   - 별도 설정을 두지 않는다. 두 값이 달라지면 창 안의 행을 지우거나(발송 제한이 풀림) 필요 없는 행을 더 두게 된다.
2. **결정 0019 전체를 0041로 대체한다.** 2026-11-03 사용자 결정(A안).
   - 0041은 0019의 결정을 그대로 옮기고 인증 기록 보관 줄과 그 근거·영향만 바꾼다.
   - 0019를 가리키는 코드·제품 문서의 참조를 0041로 바꾼다. 마이그레이션 파일(V6·V7), 다른 결정 문서와 플랜의 본문은 고치지 않는다.
   - 대안(B안): 보관 기간만 다루는 결정을 따로 둠. 참조 변경이 없지만, 결정 일부만 대체하는 규칙을 새로 만들어야 한다.
   - 프론트 주석 4개 파일이 바뀌어 CI의 `frontend` 검사가 돈다. 동작은 바뀌지 않는다.
3. **`created_at` 단일 인덱스를 추가한다.** 2026-11-03 사용자 결정.
   - 삭제가 지울 범위만 잠근다. 인덱스가 없으면 `REPEATABLE READ`의 DELETE가 훑은 모든 행을 잠가, 삭제가 끝날 때까지 새 발송의 INSERT가 기다린다.
   - 대안: 인덱스 없음. 삭제 뒤 테이블에는 24시간치만 남아 훑는 비용은 작다.
4. **삭제 작업은 `account.service.EmailVerificationPurgeJob`의 `@Scheduled(cron)` 메서드 하나다.**
   - 매일 04:30(Asia/Seoul). 04:00 정기 수집과 시각을 나눈다. cron은 `auth.email-verification.purge-cron`으로 둔다.
   - 한 번의 벌크 DELETE로 지운다. 하루 발송량이 이메일당 10건으로 제한되어 나눠 지울 만큼 쌓이지 않는다.
   - 켜고 끄는 설정은 두지 않는다. 외부 호출이 없고, 테스트에서는 cron 시각에 돌지 않는다. 테스트는 메서드를 직접 부른다.
   - 지운 행 수를 INFO 로그로 남긴다. 이메일은 남기지 않는다.
   - 실패하면 예외를 로그로 남기고 다음 날 다시 돈다(Spring 스케줄러 기본 동작). 남은 행은 다음 실행에서 함께 지워진다.
   - 한계: 하루 한 번 지우므로 실제 보관은 최대 48시간이다(발송 직후에 작업이 돈 경우). 처리방침(#111)에는 "발송 후 24시간 보관, 그 뒤 하루 안에 파기"로 적는다.

## 단계별 구현 계획

1. **마이그레이션** `V13__add_email_verification_created_index.sql`
   ```sql
   -- 발송 후 auth.mail.window(24시간)가 지난 인증 기록을 하루 한 번 지운다(#109, 결정 0041).
   -- V6의 "행을 지우지 않는다"는 결정 0019 기준이며 0041로 바뀌었다. 삭제가 지울 범위만 잠그도록 created_at 인덱스를 둔다.
   CREATE INDEX idx_email_verification_created ON email_verification (created_at);
   ```
2. **리포지토리** `EmailVerificationRepository`
   - `@Modifying @Query("delete from EmailVerification v where v.createdAt <= :cutoff") int deleteSentAtOrBefore(LocalDateTime cutoff)`.
3. **삭제 작업** `account.service.EmailVerificationPurgeJob`
   - 의존: `EmailVerificationRepository`, `AccountMailProperties`, `Clock`.
   - `@Scheduled(cron = "${auth.email-verification.purge-cron}", zone = "Asia/Seoul") @Transactional public int purge()`: `cutoff = LocalDateTime.now(clock).minus(properties.window())`로 지우고 지운 수를 돌려준다.
   - 클래스 주석: 결정 0041, 단일 인스턴스 전제.
4. **설정** `application.yaml`
   - `auth.email-verification.purge-cron: "0 30 4 * * *"`과 주석(보유 기간은 `auth.mail.window`).
   - `auth.mail` 주석에 `window`가 인증 기록 보유 기간이기도 하다는 점을 더한다.
   - 주석의 결정 번호를 0041로 바꾼다.
5. **결정 문서**
   - `docs/decisions/0041-email-verification-retention.md`
     - 제목: 가입 전 이메일 인증과 비밀번호 재설정(인증 기록 24시간 보관).
     - 배경: 0019는 인증 기록을 지우지 않아 가입하지 않은 사람·탈퇴한 사람의 이메일이 기한 없이 남았다. 개인정보 처리방침의 보유 기간과 파기 의무를 위해 대체한다. 그 밖의 결정은 0019와 같다.
     - 결정: 0019의 마지막 줄을 "발송 후 `auth.mail.window`(24시간)가 지난 행을 매일 04:30 지운다. 가입 여부와 무관하다"로 바꾼다.
     - 근거: "인증 기록을 지우지 않음"을 "24시간 보관"으로 바꾼다(설계 1·3·4). V6 주석이 남는 이유를 적는다.
     - 적용 위치: V13, `EmailVerificationPurgeJob`, `EmailVerificationRepository.deleteSentAtOrBefore`, `EmailVerificationPurgeTest`를 더한다.
     - 영향: "탈퇴 이슈는 … 판단한다"를 지운다(탈퇴한 사람의 기록도 24시간 안에 지워진다). "인증 기록 정리 작업을 두려면 이 결정을 대체한다"를 "발송 제한 창이나 인증번호·티켓 유효기간을 바꿔 24시간 넘게 기록을 써야 하면 보유 기간을 다시 정하고 이 결정을 대체한다"로 바꾼다.
   - `0019`의 상태 줄을 `대체됨 → [0041](0041-email-verification-retention.md)`으로 바꾼다.
   - `docs/decisions/README.md`의 목록 표: 0019 상태를 바꾸고 0041 행을 더한다.
   - 참조를 0041로 바꾼다: 확인된 사실의 백엔드·프론트 파일과 `application.yaml`, `docs/product/development.md`.
6. **제품 문서**
   - `DEC-AUTH`: 결정 칸에 "이메일 인증 기록은 발송 후 24시간(발송 제한 기간)만 두고 하루 한 번 파기한다(2026-11-03, #109)"를 더한다. 근거 칸에 이유(24시간 뒤에는 쓰이는 곳이 없음, 보유 기간·파기 의무)를 더하고, 한계 문구 "가입하지 않은 사람과 탈퇴한 사람의 이메일이 인증 기록에 남는다([결정 0019])"를 지운다.
   - `REQ-ACCOUNT-01` P0: "이메일 인증 기록은 발송 후 24시간 동안만 보관하고, 가입·미가입·탈퇴와 관계없이 그 뒤 파기한다"를 더한다.
7. **테스트** `account.service.EmailVerificationPurgeTest`(`@SpringBootTest(properties = "gov24.schedule.enabled=false")`, MockMvc, Testcontainers, `FixedClockConfiguration`, `SignUpSteps`)
   - 24시간 지난 기록은 가입 여부와 무관하게 지워진다: T0에 가입한 사람·인증만 받은 사람·가입 후 탈퇴한 사람, T0+1초에 인증만 받은 사람. T0+24h에 `purge()` → 앞의 셋의 행이 없고 마지막 사람의 행은 남는다. 반환값은 지운 행 수다.
   - 진행 중인 인증·가입: 번호를 받고 5분 뒤 `purge()` → 그 번호로 확인된다. 확인한 뒤 `purge()` → 그 티켓으로 가입된다.
   - 발송 간격: 보낸 직후 `purge()` → 60초가 안 되면 429다.
   - 하루 횟수: 60초 간격으로 10번 보낸다. T0+24h−1초에 `purge()` → 아무것도 지우지 않고 429 `Retry-After: 1`이다. T0+24h에 `purge()` → 첫 행만 지워지고 다음 요청이 204다(`EmailVerificationApiTest.최근_24시간에는_10번까지_보낸다`와 같은 결과).
   - 스케줄 등록: `ScheduledTaskHolder`에서 `EmailVerificationPurgeJob.purge`의 cron이 `0 30 4 * * *`이다.
   - 기존 테스트는 결정 번호 주석 외에는 바꾸지 않고 통과한다.

## 완료 조건 매핑

- [ ] 삭제 작업이 돈 뒤 발송 후 24시간이 지난 인증 기록이 남지 않는다. 가입한 사람, 가입하지 않은 사람, 탈퇴한 사람 모두 같다. → 설계 1·4. `EmailVerificationPurgeTest`의 가입·미가입·탈퇴 삭제.
- [ ] 발송 후 24시간이 지나지 않은 기록은 남는다. 발송 간격·하루 횟수 제한과 진행 중인 인증·가입이 삭제 작업 전후로 같게 동작한다. → 설계 1. `EmailVerificationPurgeTest`의 경계(T0+1초), 진행 중 인증·가입, 발송 간격, 하루 횟수.
- [ ] 결정 문서, `DEC-AUTH`, `REQ-ACCOUNT-01`에 보유 기간이 적혀 있고, '이메일이 인증 기록에 남는다'는 한계 문구가 없다. → 설계 2. 단계 5·6.

## 검증 명령

- `backend/`에서 `./gradlew test`
- `frontend/`에서 `npm run lint`, `npm run typecheck`, `VITE_API_BASE_URL=http://localhost:8080 npm run build`(CI와 같은 값). 주석만 바뀌지만 CI `frontend` 검사 대상이다.
- `grep -rn "0019" backend/src frontend/src docs/product`의 결과가 V6·V7 마이그레이션뿐이다.
- `grep -n "인증 기록에 남는다" docs/product`의 결과가 없다.

## 결정 필요

없음
