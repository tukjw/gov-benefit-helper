status: done

# 구현 계획 — #52 로그인과 탈퇴의 비밀번호 확인에 시도 속도 제한 적용

브랜치: `feat/52-login-rate-limit` (develop `d55de2b` 기준)

## 목표 및 범위

- 목표: 로그인과 탈퇴의 비밀번호 확인 실패가 반복되면 계정을 잠그지 않고 (이메일+IP)별·IP별로 시도 속도를 제한한다(`REQ-ACCOUNT-01`, `DEC-AUTH`).
- 포함:
  - 실패 기록을 앱 메모리에 두는 제한기(`LoginAttemptLimiter`)와 설정(`auth.login-attempt.*`).
  - `POST /api/auth/login`, `POST /api/me/withdrawal`에 제한 적용. 제한에 걸리면 기존 429 `TOO_MANY_REQUESTS`와 `Retry-After`.
  - API 명세(두 API의 429 응답), `ApiDocsTest`.
  - 결정 문서 0035, `DEC-AUTH` 행.
- 제외:
  - 여러 IP로 나눠 한 계정을 노리는 공격의 계정 단위 상한: 계정 주인이 막히지 않게 하려고 두지 않는다(설계 1). 한계로 적는다.
  - 기기 쿠키로 주인을 구분하는 방식: 필요해지면 새 이슈.
  - Cloudflare 요청량 규칙: 앱 제한과 별개다. 이 이슈에서 바꾸지 않는다.
  - 여러 앱 인스턴스가 실패 기록을 공유하는 저장소(Redis 등): 운영은 앱 하나다(설계 2).
  - 프론트의 로그인·탈퇴 화면 429 안내 문구: 별도 이슈(설계 9).

## 확인된 사실과 제약

- 요청자 IP: `application.yaml`이 `CF-Connecting-IP`를 `getRemoteAddr()`로 쓴다(결정 0034, `ClientIpTest`). 컨트롤러에서 `HttpServletRequest.getRemoteAddr()`로 받으면 된다. MockMvc 요청의 기본 주소는 `127.0.0.1`이다.
- 429 응답: `RateLimitedException(Duration)`을 던지면 `GlobalExceptionHandler`가 429와 `Retry-After`(초, 올림, 최소 1)를 붙인다(결정 0009·0019).
- `AccountService.login`: 72바이트 초과 → 실패, 가입되지 않은 이메일 → 더미 해시 비교 후 같은 실패(결정 0013). 제한 확인은 이 분기들보다 먼저 해야 가입 여부가 드러나지 않는다.
- `AccountService.withdraw`: `findByIdForUpdate`로 계정 행을 잠근 뒤 비밀번호를 비교한다. 계정의 이메일은 이때 얻는다.
- 비밀번호 비교는 BCrypt라 요청 하나가 수십~수백 ms 걸린다. "확인 → 비교 → 실패면 기록" 순서면 그 사이 들어온 병렬 요청이 모두 확인을 통과한다.
- 시각: 서비스는 `Clock` bean을 쓴다. 테스트는 `FixedClockConfiguration`·`MutableClock`(결정 0033).
- 테스트는 하나의 Spring 컨텍스트와 DB를 공유하고, `DatabaseCleanupListener`가 테스트 메서드마다 DB만 비운다(결정 0033). 메모리 상태는 비워지지 않는다. 기존 로그인·탈퇴 실패 테스트가 모두 `127.0.0.1`에서 보내므로, 기록을 비우지 않으면 IP 제한이 다른 테스트의 결과를 바꾼다.
- `@EnableScheduling`이 `CollectionConfig`에 있어 앱 전체에 적용된다.
- 운영은 app VM 하나에 앱 컨테이너 하나다(결정 0034). 배포는 컨테이너를 다시 띄운다.
- 프론트: 로그인·탈퇴 화면은 429를 `GENERIC_ERROR`("잠시 뒤 다시 시도해 주세요")로 보인다. `formErrors.retryAtOf`는 있지만 문구가 메일 발송용("다시 받을 수 있습니다")이다.

## 설계 결정

1. **(이메일+IP) 묶음과 IP 두 기준으로 센다.** 2026-10-29 사용자 결정. 결정 문서 0035.
   - 이메일은 `Emails.normalize`한 값이다. IP는 IPv4는 주소 그대로, IPv6는 앞 64비트(`/64`)로 묶는다. IPv6는 한 사용자가 대역 안의 여러 주소를 쓸 수 있다.
   - 둘 중 하나라도 한도에 차 있으면 429다. `Retry-After`는 두 기준 중 더 긴 대기 시간이다.
   - 같은 IP의 같은 이메일 실패는 맞는 비밀번호로도 막는다. 다른 IP의 계정 주인은 막지 않는다(이슈 완료 조건).
   - 대안: 이메일만. 공격자가 실패를 쌓아 주인의 로그인을 막을 수 있어 계정 잠금과 같아진다.
   - 대안: 이메일 전체에 느슨한 상한 추가. 상한에 걸리면 주인도 막힌다.
   - 대안: 기기 쿠키. 주인도 지키고 분산 공격도 막지만 쿠키 발급·검증이 늘어난다.
   - 한계: 여러 IP를 돌려 한 계정을 노리는 공격은 IP마다 한도까지 시도할 수 있다. 국내 모바일 통신사처럼 여러 사람이 한 IP를 쓰면 IP 한도를 함께 쓴다.
2. **실패 기록은 앱 메모리에 둔다.** 2026-10-29 사용자 결정. 결정 문서 0035.
   - DB 쓰기와 오래된 행 정리가 없고, 동시 요청을 원자적으로 다루기 쉽다. 운영은 앱 하나다.
   - 대안: MySQL 테이블. 재시작에도 남고 앱을 늘려도 맞지만, 실패마다 쓰기와 행 정리, 동시성 처리(행 잠금이나 원자적 증가)가 필요하다.
   - 대안: Redis. 새 인프라이고 `DEC-OPS`가 근거가 생길 때 고르기로 했다.
   - 한계: 배포·재시작하면 기록이 사라진다. 앱을 여러 개 띄우면 인스턴스마다 따로 세어 한도가 인스턴스 수만큼 늘어난다. 앱을 늘리면 이 결정을 대체한다.
3. **최근 `window` 안의 실패 시각을 기준마다 기록하고 센다(슬라이딩 로그).** 2026-10-29 사용자 결정.
   - 메일 발송 제한(`SendRateLimit`)과 같은 셈법이다. `Retry-After`는 창 안에서 가장 오래된 실패 + `window` − 지금이다.
   - 대안: 키당 고정 창 카운터. 창 경계에서 한도의 두 배까지 허용된다.
   - 대안: 실패마다 대기 시간을 두 배로 늘림. 대기 시간과 안내가 복잡해진다.
4. **비밀번호를 비교하기 전에 시도를 원자적으로 예약하고, 성공하면 되돌린다.**
   - 예약: 두 기준 모두 한도 미만이면 지금 시각을 두 기준에 함께 기록한다. 기준 하나라도 차 있으면 기록하지 않고 429다. 키 단위 잠금(`ConcurrentHashMap.compute`)이 아니라 두 키를 함께 봐야 하므로 제한기 전체를 `synchronized`로 묶는다. 메모리 연산만이라 비용이 작다.
   - 성공: (이메일+IP) 기록을 모두 지우고, IP 기록에서는 이번 예약만 지운다. IP 기록을 모두 지우면 공격자가 자기 계정으로 로그인해 IP 한도를 초기화할 수 있다.
   - 실패: 예약을 그대로 실패로 남긴다.
   - 대안: 비교 뒤 실패만 기록. BCrypt 비교 동안 들어온 병렬 요청이 모두 확인을 통과해 한도를 넘는다.
   - 가입되지 않은 이메일과 72바이트 초과 비밀번호도 같은 실패로 센다. 예약은 계정 조회보다 먼저라 429는 가입 여부와 관계없이 같은 시점에 같은 응답이다.
5. **탈퇴의 비밀번호 확인 실패는 로그인과 같은 기록을 쓴다.** 2026-10-29 사용자 결정.
   - 탈퇴는 그 계정의 이메일과 요청 IP로 같은 두 기준에 예약한다. 로그인과 탈퇴를 합쳐 한 계정에 시도할 수 있는 횟수가 한도로 묶인다.
   - 대안: 사용자 ID로 따로 셈. 탈취한 액세스 토큰이 있으면 로그인과 탈퇴 양쪽에서 각각 한도까지 시도할 수 있다.
   - 탈퇴 성공은 계정이 지워지므로 (이메일+IP) 기록을 지우는 것 외에 할 일이 없다.
6. **오래된 기록은 접근할 때와 주기 작업에서 지운다.**
   - 키에 접근할 때 창 밖의 시각을 지우고, 비면 키를 지운다.
   - 접근이 끊긴 키는 `@Scheduled(fixedDelayString = "${auth.login-attempt.window}")`로 전체를 훑어 지운다.
   - 메모리 상한: IP마다 창 안에 IP 한도만큼만 실패가 기록되므로, 기록 수는 (창 안의 IP 수 × IP 한도)를 넘지 않는다.
7. **테스트는 메서드마다 제한기 기록을 비운다.**
   - `LoginAttemptLimiter.clear()`를 두고, 새 `LoginAttemptResetListener`(`TestExecutionListener`)를 `spring.factories`에 더해 `DatabaseCleanupListener`처럼 테스트 메서드마다 부른다. 0033의 "테스트 메서드 사이에 데이터를 이어 쓰지 않는다"를 메모리 상태에도 적용한다.
   - 대안: 테스트마다 다른 IP로 요청. 기존 로그인·탈퇴 테스트를 모두 고쳐야 하고 빠뜨리면 순서에 따라 결과가 달라진다.
8. **수치는 창 15분, (이메일+IP) 실패 5회, IP 실패 30회다.** 2026-10-29 사용자 결정. `DEC-AUTH`에 기록한다.
   - (이메일+IP) 5회는 오타 몇 번을 허용하면서 한 IP의 대입을 하루 480회로 묶는다.
   - IP 30회는 통신사 공용 IP 뒤의 여러 사용자가 함께 써도 정상 사용자가 막히지 않을 만큼 넉넉하게 둔다.
   - 값은 설정(`auth.login-attempt.*`)이라 운영 중 바꿀 수 있다.
9. **비밀번호 재설정을 마치면 그 이메일의 (이메일+IP) 기록을 모두 지운다.** 2026-10-29 사용자 결정. 결정 문서 0035.
   - 재설정은 메일 소유를 확인한 것이다. 같은 IP에서 몇 번 틀리고 재설정한 주인이 창이 끝날 때까지 기다리지 않게 한다.
   - IP 기록은 지우지 않는다. 설계 4의 성공과 같은 이유다.
   - `PasswordResetService.reset` 트랜잭션 안에서 지운다. 뒤에서 롤백되면 기록만 지워지고 비밀번호는 그대로인데, 유효한 재설정 링크를 가진 사람만 일으킬 수 있고 얻는 것은 시도 몇 번이라 커밋 후 처리를 두지 않는다.
   - 대안: 지우지 않음. 구현이 단순하지만 재설정 직후 같은 IP에서 최대 15분 동안 429를 받을 수 있다.
   - 프론트 안내 문구는 별도 이슈로 한다(2026-10-29 사용자 결정). 지금 화면은 429를 일반 오류 문구로 보이므로 동작은 막히지 않는다. #52는 `area/backend`이고 PR 하나에 이슈 하나다.

## 단계별 구현 계획

1. **설정** `account.config.LoginAttemptProperties`
   - `@ConfigurationProperties("auth.login-attempt") record LoginAttemptProperties(Duration window, int maxFailuresPerEmailAndIp, int maxFailuresPerIp)`.
   - `application.yaml`의 `auth:` 아래 `login-attempt:`에 `window: 15m`, `max-failures-per-email-and-ip: 5`, `max-failures-per-ip: 30`과 주석(결정 0035, 설계 8).
   - `AccountConfig`의 `@EnableConfigurationProperties`에 더한다.
2. **IP 묶기** `account.domain.ClientIps`
   - `static String limitKey(String remoteAddr)`: IPv4는 그대로, IPv6는 `/64`로 자른 표기. 해석할 수 없으면 원문 그대로.
3. **제한기** `account.service.LoginAttemptLimiter`(`@Component`)
   - 상태: `Map<String, Map<String, Deque<Instant>>> byEmail`(이메일 → IP 키 → 실패 시각)과 `Map<String, Deque<Instant>> byIp`. 이메일로 묶어 두면 재설정 때 그 이메일의 기록을 한 번에 지운다(설계 9).
   - `synchronized Attempt reserve(String email, String remoteAddr)`: 지금 시각은 `Clock`. 두 기준을 창 밖 정리 후 센다. 하나라도 한도면 `RateLimitedException(더 긴 대기)`. 아니면 두 덱에 같은 `Instant`를 넣고 `Attempt(emailAndIpKey, ipKey, at)`를 돌려준다.
   - `synchronized void succeeded(Attempt attempt)`: (이메일+IP) 키 제거, IP 덱에서 `attempt.at` 하나 제거.
   - `synchronized void clearEmail(String email)`: 그 이메일의 (이메일+IP) 기록을 모두 지운다. IP 기록은 그대로다.
   - `@Scheduled(...) synchronized void evictExpired()`, `synchronized void clear()`(테스트용, 주석에 명시).
   - 구현 중 추가: `isEmpty()`. 주기 정리와 테스트 메서드마다 비우는 것을 테스트에서 확인한다.
   - 클래스 주석: 기준·셈법·예약 이유·메모리 저장의 한계(결정 0035).
4. **서비스** `account.service.AccountService`
   - `login(LoginRequest request, String remoteAddr)`: 이메일 정규화 → `limiter.reserve` → 기존 분기. 성공 직전에 `limiter.succeeded`. 실패 분기는 그대로 던진다.
   - `withdraw(Long userId, String password, String remoteAddr)`: `findByIdForUpdate` → `limiter.reserve(account.getEmail(), remoteAddr)` → 비교. 일치하면 `succeeded` 후 삭제.
   - 메서드 주석에 시도 제한을 더한다.
   - `PasswordResetService.reset`: 비밀번호를 바꾼 뒤 `limiter.clearEmail(account.getEmail())`(설계 9).
5. **컨트롤러**
   - `AuthController.login`, `MeController.withdraw`가 `HttpServletRequest`를 받아 `getRemoteAddr()`를 넘긴다.
   - 두 메서드에 `@ApiResponse(responseCode = "429")`: `TOO_MANY_REQUESTS`, `Retry-After` 헤더 설명(다시 시도할 수 있을 때까지의 초). 로그인 설명에 "가입 여부와 관계없이 같은 기준으로 제한한다"를 넣는다. 메일 발송 API의 429 설명 형식을 따른다.
6. **테스트**
   - `account.service.LoginAttemptLimiterTest`(단위, `MutableClock`)
     - 한도 미만이면 예약된다. (이메일+IP) 한도 / IP 한도에 차면 `RateLimitedException`이고 대기 시간이 가장 오래된 실패 + 창 − 지금이다.
     - 창이 지나면 다시 예약된다.
     - 성공하면 (이메일+IP) 기록은 사라지고 IP 기록의 다른 실패는 남는다.
     - 같은 이메일이라도 다른 IP는 영향이 없다. IPv6 같은 `/64`는 같은 IP로 센다.
     - `evictExpired` 뒤 빈 키가 남지 않는다.
   - `account.controller.LoginRateLimitApiTest`(`@SpringBootTest`, MockMvc, `FixedClockConfiguration`, `with(request -> request.setRemoteAddr(...))`)
     - 같은 이메일·IP로 한도만큼 실패하면 다음은 맞는 비밀번호로도 429와 `Retry-After`이고, 시계를 그만큼 넘기면 200이다.
     - 같은 IP에서 서로 다른 이메일로 IP 한도만큼 실패하면 다음은 429다.
     - 제한 중에도 다른 IP에서 계정 주인이 로그인하면 200이다. 같은 IP에서 비밀번호 재설정 요청이 200이다.
     - 가입된 이메일과 가입되지 않은 이메일을 같은 횟수만큼 실패시키면 429의 상태·본문·`Retry-After`가 같다.
     - 탈퇴의 비밀번호 실패가 한도를 넘으면 429이고 계정이 남는다. 탈퇴 실패와 로그인 실패가 같은 (이메일+IP) 한도를 함께 쓴다.
     - 성공한 로그인 뒤에는 (이메일+IP) 실패가 다시 0부터 센다.
     - (이메일+IP) 한도로 429인 상태에서 비밀번호를 재설정하면 같은 IP에서 새 비밀번호로 곧바로 로그인된다. IP 한도의 실패는 남는다.
   - `account.service.LoginAttemptConcurrencyTest`(`CountDownLatch`, 기존 동시성 테스트 형식): 같은 이메일·IP로 틀린 비밀번호 로그인을 한도의 몇 배만큼 동시에 보내면 `INVALID_CREDENTIALS`는 한도 수만큼이고 나머지는 429다.
   - `LoginAttemptResetListener`와 `spring.factories` 등록. `SharedTestDatabaseTest`처럼 테스트 시작 시 제한기가 비어 있는지 확인하는 테스트를 하나 둔다.
     - 구현 중 추가: `LoginAttemptResetTest`(`@RepeatedTest(2)`로 앞 실행이 남긴 기록이 지워졌는지 본다), `account.domain.ClientIpsTest`(IPv4, IPv6 `/64`, IPv4-mapped, 해석 불가).
   - `ApiDocsTest`: 두 API의 응답 코드에 429가 있다.
   - 기존 로그인·탈퇴 테스트는 시그니처 변경(서비스를 직접 부르는 곳) 외에 바꾸지 않고 통과한다.
7. **문서**
   - `docs/decisions/0035-login-attempt-limit.md`: 설계 1~5·9의 요지, 근거, 한계(분산 공격, 공용 IP, 재시작 초기화, 다중 인스턴스). 결정 목록에 행 추가.
   - `docs/product/development.md`의 `DEC-AUTH(로그인·탈퇴)` 행
     - 결정: "로그인 실패는 … 수치는 구현 플랜에서 정한다"를 "로그인 실패는 계정을 잠그지 않고 같은 이메일·IP에서 15분에 5회, 같은 IP에서 15분에 30회로 시도 속도를 제한한다. 탈퇴의 비밀번호 확인 실패도 같은 기록으로 센다. 비밀번호를 재설정하면 그 이메일의 제한이 풀린다(2026-10-29, #52)"로 바꾼다.
     - 한계: "로그인과 탈퇴의 비밀번호 확인에 아직 시도 제한이 없다(#52)"를 지우고 결정 0035의 한계를 짧게 적는다.
   - 같은 파일 126행의 "로그인 실패 제한 수치와 … 사용자 식별 구현 플랜에서 정한다"에서 제한 수치 부분을 정리한다.
   - 구현 중 추가: 같은 파일의 결정 기록 문단 끝에 "DEC-AUTH의 로그인 실패 제한 기준과 수치를 2026-10-29에 정했다. 기록은 #52에서 했다"를 더한다. 이전 결정 변경도 같은 형식으로 남겼다.

## 완료 조건 매핑

- [ ] 같은 이메일로 로그인 실패가 정한 횟수를 넘으면 맞는 비밀번호로도 429와 `Retry-After`를 받고, 그 시간이 지나면 다시 로그인할 수 있다. → 설계 1·3. `LoginRateLimitApiTest`.
- [ ] 같은 IP에서 여러 이메일로 로그인 실패가 정한 횟수를 넘으면 429를 받는다. → 설계 1. `LoginRateLimitApiTest`.
- [ ] 제한에 걸린 동안에도 계정은 잠기지 않는다. 다른 IP의 계정 주인이나 비밀번호 재설정은 영향을 받지 않는다. → 설계 1. `LoginRateLimitApiTest`의 다른 IP 로그인·재설정 요청.
- [ ] 가입된 이메일과 가입되지 않은 이메일이 제한 응답으로 구분되지 않는다. → 설계 4. `LoginRateLimitApiTest`.
- [ ] 탈퇴의 비밀번호 확인 실패가 정한 횟수를 넘으면 429를 받는다. → 설계 5. `LoginRateLimitApiTest`.
- [ ] 제한 수치가 `DEC-AUTH`에 기록되어 있다. → 단계 7.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인: `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤
  ```sh
  for i in $(seq 1 6); do
    curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/auth/login \
      -H 'Content-Type: application/json' -d '{"email":"a@example.com","password":"wrong-password"}'
  done
  curl -si -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
    -d '{"email":"a@example.com","password":"<맞는 비밀번호>"}'
  ```
  처음 5번은 401, 여섯 번째는 429이고, 마지막 요청(맞는 비밀번호)도 429와 `Retry-After`(900초 이하)를 받는지 본다.

## 결정 필요

없음
