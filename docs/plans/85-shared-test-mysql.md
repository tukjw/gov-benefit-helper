status: in-progress

# 구현 계획 — #85 모든 테스트 컨텍스트가 MySQL 컨테이너 하나를 공유하도록 변경

브랜치: `test/85-shared-test-mysql` (develop `288af7c` 기준)

## 목표 및 범위

- 목표: `./gradlew test` 한 번에 MySQL 컨테이너가 하나만 뜨게 한다. 컨텍스트가 닫혀도 컨테이너는 살아 있고, DB를 함께 써도 테스트끼리 데이터나 시각이 섞이지 않는다.
- 포함:
  - `TestcontainersConfiguration`의 컨테이너 수명과 접속 정보 등록 방식.
  - 모든 Spring 테스트에 공통으로 적용하는 테이블 정리. 클래스마다 있는 전체 테이블 정리는 지운다.
  - 클래스마다 있는 고정 시계 설정 11개를 공통 설정 하나로 합친다.
  - 위 규칙을 결정 문서로 올린다.
- 제외:
  - 시계 외의 이유로 갈리는 컨텍스트 합치기. 예: 클래스마다 다른 stub 서버의 `@DynamicPropertySource`, `@AutoConfigureMockMvc` 유무, `properties` 차이.
  - 테스트 병렬 실행, Gradle `maxParallelForks` 변경.
  - CI 워크플로(`ci.yml`)와 러너 변경.
  - 테스트 단언 내용 변경. 정리 코드와 시계 설정 말고는 고치지 않는다.

## 확인된 사실과 제약

- 컨테이너 설정
  - `TestcontainersConfiguration.mysqlContainer()`는 `@Bean @ServiceConnection`이다. `mysql:8.4`, DB 이름 `gov_benefit_test`, `--character-set-server=utf8mb4`, `--collation-server=utf8mb4_0900_ai_ci`, `TZ=Asia/Seoul`, URL 파라미터 `serverTimezone=Asia/Seoul`을 쓴다.
  - Spring 테스트 클래스는 모두 `@Import(TestcontainersConfiguration.class)`를 쓴다. 컨텍스트마다 bean이 따로 만들어지므로 컨테이너도 컨텍스트마다 하나씩 뜬다.
- 컨테이너가 bean이면 컨텍스트를 닫을 때 같이 닫힌다(Spring Boot 4.1.1 `spring-boot-testcontainers` 소스로 확인).
  - `TestcontainersLifecycleBeanPostProcessor.postProcessBeforeDestruction`은 destroy 메서드가 `""`인 `Startable` bean을 직접 `close()`한다. destroy 메서드가 `""`가 아니면 컨테이너가 이미 `AutoCloseable`이라 프레임워크가 `close()`를 부른다. 어느 쪽이든 닫힌다.
  - 예외는 reuse 컨테이너뿐인데, `~/.testcontainers.properties`의 `testcontainers.reuse.enable` 같은 실행 환경 설정에 달려 있다.
  - `@ImportTestcontainers`의 static 필드도 `ContainerFieldsImporter`가 bean 정의로 등록하므로 마찬가지로 닫힌다.
  - 컨텍스트가 닫히는 경우는 JVM 종료, 컨텍스트 캐시 초과(기본 32개, LRU), `@DirtiesContext`다. 지금 저장소에 `@DirtiesContext`는 없다.
- Gradle `test` 태스크에 `maxParallelForks`·`forkEvery` 설정이 없다. 테스트 JVM은 하나이고, JUnit 병렬 실행 설정(`junit-platform.properties`)도 없다.
- 테이블과 시드
  - Flyway가 만드는 테이블은 16개다: `collection_run`, `collection_run_step`, `collection_quarantine`, `source_record`, `source_record_history`, `benefit`, `benefit_detail`, `collection_failure`, `bookmark`, `refresh_token`, `email_verification`, `password_reset_token`, `user_account`, `user_profile`, `user_profile_attribute`, `region`. 여기에 `flyway_schema_history`가 더해진다.
  - 마이그레이션으로 데이터를 넣는 테이블은 `region`뿐이다(V8, V11). `RegionSeedTest`가 시도 16개, 시군구 227개, 교육청 16곳을 확인한다.
  - `bookmark.benefit_id`(V10)와 `collection_*`·`source_record_history`·`benefit_detail`의 FK에는 `ON DELETE CASCADE`가 없다. 지울 순서를 틀리면 FK 위반이 난다.
- 지금 클래스별 정리
  - 정리는 모두 `@BeforeEach`에서 `JdbcTemplate`으로 한다. `@BeforeAll`로 데이터를 넣거나 메서드 순서에 기대는 테스트는 없다.
  - 정리 범위가 클래스마다 다르다. 계정 테스트는 `user_account`·`email_verification`만, 수집 테스트는 수집·혜택 테이블 9개만, 매칭·관심 혜택 테스트는 11개를 지운다. `LoginApiTest`, `CorsTest`, `ApiDocsTest`, `RegionSeedTest` 등은 아무것도 지우지 않는다.
  - 지금은 컨텍스트마다 DB가 따로라 정리 범위가 달라도 같은 컨텍스트 안에서만 맞으면 됐다. DB를 하나로 합치면 다른 클래스가 남긴 행이 보인다.
  - 테스트 본문 안의 부분 삭제(`ProfileApiTest`의 `DELETE FROM user_account WHERE id = ?`, `DELETE FROM benefit WHERE service_id = ?`)는 테스트 시나리오의 일부다.
  - `CollectionSchedulerTest.정기_수집은_전체_수집을_한_번_실행한다`는 정리를 테스트 본문 첫머리에서 한다.
  - `Collection*`, `Benefit*`, `Bookmark*`, `Match*`, `ApiDocsTest` 등은 `@BeforeEach`에서 stub 서버 상태도 함께 초기화한다. 이 부분은 그대로 둔다.
- 고정 시계
  - 11개 클래스가 각자 `FixedClockConfig`(`@Primary MutableClock`)를 nested `@TestConfiguration`으로 두고 `@Import`에도 적는다: `SignUpApiTest`, `EmailVerificationApiTest`, `PasswordResetApiTest`, `RefreshTokenApiTest`, `WithdrawalApiTest`, `ProfileApiTest`, `SignUpConcurrencyTest`, `BookmarkApiTest`, `MatchApiTest`, `MatchListApiTest`, `PublicMatchApiTest`.
  - 11개 모두 `@BeforeEach`에서 `clock.set(START)`를 한다. 클래스별 `START`는 2026-10-14 ~ 2026-10-20 사이 값이다.
  - 공통 설정으로 바꾸면 컨텍스트가 하나로 합쳐지는 6개: `SignUpApiTest`, `EmailVerificationApiTest`, `PasswordResetApiTest`, `RefreshTokenApiTest`, `WithdrawalApiTest`, `ProfileApiTest`. 모두 `gov24.schedule.enabled=false`와 `@AutoConfigureMockMvc`를 쓴다.
  - 나머지 5개는 그대로 따로 남는다. `Bookmark`·`Match`·`MatchList`·`PublicMatch` ApiTest는 클래스마다 stub 서버를 `@DynamicPropertySource`로 연결하고, `SignUpConcurrencyTest`는 MockMvc를 쓰지 않는다.
- 스케줄러: `CollectionSchedulerTest`만 `gov24.schedule.enabled=true`이고, 등록되는 작업은 매일 04시 cron이다. 그 컨텍스트가 캐시에 남아 있어도 테스트 실행 중 수집이 끼어들지 않는다.
- CI: `test` job은 `ubuntu-24.04`, `timeout-minutes: 20`이다. 최근 성공한 PR의 `test` job은 7~9분 걸렸다(#73 7분 05초, #74 7분 19초, #79 9분 01초).

## 설계 결정

1. **컨테이너는 static 싱글턴으로 띄우고 bean으로 등록하지 않는다.** 접속 정보는 `DynamicPropertyRegistrar` bean으로 `spring.datasource.url`·`username`·`password`에 넣는다. bean으로 등록되는 것은 registrar뿐이라 컨텍스트가 닫혀도 컨테이너는 닫히지 않는다. 컨테이너는 JVM이 끝날 때 Testcontainers의 Ryuk가 지운다. 결정 문서 [0032](../decisions/0032-test-shared-mysql.md)로 올린다.
   - 제외한 대안 1, `@Bean @ServiceConnection`이 static 인스턴스를 돌려주는 방식: 컨텍스트가 닫히면 공유 컨테이너가 닫힌다(사실 참고).
   - 제외한 대안 2, reuse 컨테이너: 실행 환경 설정에 기대므로 CI와 로컬에서 동작이 달라진다.
   - 제외한 대안 3, `jdbc:tc:` URL: 시작 명령과 `TZ` 환경 변수를 지금처럼 코드로 둘 수 없다.
2. **DB 정리는 Spring `TestExecutionListener` 하나로 모든 Spring 테스트에 자동 적용한다.** 각 테스트 메서드 전(`beforeTestMethod`)에 `flyway_schema_history`와 시드 테이블 `region`을 뺀 모든 테이블을 비운다. 결정 문서 0032에 함께 적는다.
   - 등록은 `src/test/resources/META-INF/spring.factories`로 한다. 테스트 클래스에 어노테이션을 더하지 않아도 되고, 새 테스트도 따로 신경 쓰지 않아도 적용된다.
   - `SpringExtension`의 `beforeEach`가 클래스의 `@BeforeEach`보다 먼저 돌므로, 정리한 뒤에 각 클래스가 데이터를 넣는다.
   - 비울 테이블은 `information_schema.tables`에서 현재 스키마의 테이블을 읽고 제외 목록을 뺀다. 새 테이블은 자동으로 포함되고, 새 시드 테이블만 제외 목록에 더한다. 모든 컨텍스트가 같은 DB를 쓰므로 목록은 한 번 읽어 static 필드에 둔다.
   - `TRUNCATE`가 아니라 `DELETE`를 쓴다. `TRUNCATE`는 테이블을 다시 만드는 DDL이라, Spring 테스트 메서드마다 15개 테이블에 쓰면 실행 시간이 늘어날 수 있다. 비어 있는 테이블의 `DELETE`는 거의 비용이 없다. `AUTO_INCREMENT`가 이어지는 것은 문제가 없다. 지금도 같은 클래스 안에서는 id가 이어지고, id 값에 기대는 테스트가 없다.
   - FK 순서를 신경 쓰지 않도록 같은 커넥션에서 `SET FOREIGN_KEY_CHECKS = 0`으로 끄고 지운 뒤, `finally`에서 다시 켠다. 세션 변수라 `JdbcTemplate.execute(ConnectionCallback)`로 커넥션 하나에서 처리한다. 다시 켜지 않은 채 커넥션 풀로 돌려주면 다른 테스트의 FK 검증이 꺼지기 때문이다.
   - 메서드마다 지우는 이유: 같은 클래스 안에서도 지금 모든 클래스가 메서드마다 지우고 있다. 클래스 단위로 지우면 지금 동작보다 약해진다.
   - 제외한 대안: 클래스별 `@BeforeEach` 정리를 보강하는 방식. 변경은 작지만, 정리 범위를 빠뜨리는 같은 문제가 테스트를 더할 때마다 다시 생긴다.
3. **클래스별 `@BeforeEach`의 전체 테이블 정리는 지운다.** 공통 정리와 겹치고, 남겨 두면 정리가 필요한 것처럼 읽혀 범위가 다시 갈린다. 테스트 본문 안의 부분 삭제는 시나리오이므로 남긴다. `@BeforeEach`의 특정 행 삭제(`WithdrawalApiTest`, `BookmarkConcurrencyTest`의 `DELETE FROM benefit WHERE service_id = ?`)는 공통 정리 뒤라 효과가 없으므로 지운다.
4. **고정 시계는 공통 `FixedClockConfiguration` 하나로 둔다.** `@Primary MutableClock` bean 하나를 만들고, 기본 시각은 `2026-10-16T01:00:00Z`(`Asia/Seoul`)로 둔다. 각 테스트는 지금처럼 `@BeforeEach`에서 `clock.set(START)`로 자기 기준 시각을 맞춘다. 이 클래스를 `@Import`하는 테스트끼리 다른 설정이 같으면 컨텍스트를 함께 쓴다. 결정 문서 0032에 함께 적는다.

## 단계별 구현 계획

1. **플랜 상태**: 착수할 때 이 파일의 `status`를 `in-progress`로 바꾼다.
2. **바꾸기 전 기준값 측정** (develop `288af7c`, 이 작업 전 상태)
   - 동시에 떠 있는 MySQL 컨테이너 최대 수와 메모리 합계를 잰다. 측정 방법은 검증 명령 1을 따른다.
   - 컨텍스트 수를 잰다. 측정 방법은 검증 명령 2를 따른다.
   - 로컬 `./gradlew test` 소요 시간을 잰다.
   - 결과는 PR 설명에 적는다.
3. **공유 컨테이너** `backend/src/test/java/kr/govbenefit/TestcontainersConfiguration.java`
   - `private static final MySQLContainer MYSQL`에 지금 설정을 그대로 옮기고, static 블록에서 `MYSQL.start()`를 부른다. 기존 Javadoc(문자 집합, 시간대, DB 이름 설명)은 필드로 옮긴다.
   - `mysqlContainer()` bean과 `@ServiceConnection`을 지운다.
   - `@Bean DynamicPropertyRegistrar mysqlProperties()`를 둔다. `spring.datasource.url`(`MYSQL::getJdbcUrl`), `spring.datasource.username`, `spring.datasource.password`를 등록한다. `getJdbcUrl()`에는 `withUrlParam`의 `serverTimezone`이 포함된다.
   - 클래스 Javadoc에 컨테이너를 bean으로 두지 않는 이유(설계 1)를 적는다.
4. **공통 DB 정리**
   - `backend/src/test/java/kr/govbenefit/DatabaseCleanupListener.java`를 만든다. `AbstractTestExecutionListener`를 상속한다.
   - `beforeTestMethod`에서 컨텍스트의 `JdbcTemplate`을 `getBeanProvider(...).getIfAvailable()`로 얻는다. 없으면 아무것도 하지 않는다.
   - 테이블 목록을 처음 한 번 `information_schema.tables`에서 `table_schema = DATABASE()`로 읽는다. `KEEP_TABLES = Set.of("flyway_schema_history", "region")`를 빼고 static 필드에 둔다.
   - `ConnectionCallback` 안에서 `SET FOREIGN_KEY_CHECKS = 0` → 각 테이블 `DELETE FROM` → `finally`에서 `SET FOREIGN_KEY_CHECKS = 1` 순서로 실행한다(설계 2).
   - `getOrder()`는 `DependencyInjectionTestExecutionListener`보다 뒤로 둔다(`Ordered.LOWEST_PRECEDENCE`).
   - `backend/src/test/resources/META-INF/spring.factories`에 `org.springframework.test.context.TestExecutionListener=kr.govbenefit.DatabaseCleanupListener`를 등록한다.
5. **공통 고정 시계** `backend/src/test/java/kr/govbenefit/FixedClockConfiguration.java`
   - `@TestConfiguration(proxyBeanMethods = false)`에 `@Bean @Primary MutableClock testClock()`을 둔다(설계 4).
   - 11개 클래스에서 nested `FixedClockConfig`를 지우고, `@Import`를 `{TestcontainersConfiguration.class, FixedClockConfiguration.class}`로 바꾼다. 대상: `SignUpApiTest`, `EmailVerificationApiTest`, `PasswordResetApiTest`, `RefreshTokenApiTest`, `WithdrawalApiTest`, `ProfileApiTest`, `SignUpConcurrencyTest`, `BookmarkApiTest`, `MatchApiTest`, `MatchListApiTest`, `PublicMatchApiTest`.
   - 쓰이지 않게 된 import(`TestConfiguration`, `Bean`, `Primary`, `ZoneId` 등)를 정리한다. `START`와 `clock.set(START)`는 남긴다.
6. **클래스별 정리 제거**(설계 3)
   - `@BeforeEach`의 `DELETE FROM <table>`과 테이블 목록 반복문을 지운다. 대상: `account/controller`의 `SignUp`·`EmailVerification`·`PasswordReset`·`RefreshToken`·`Withdrawal`·`Login` ApiTest, `account/service`의 `SignUp`·`EmailVerification`·`RefreshToken`·`Withdrawal` ConcurrencyTest, `AccessTokenAuthTest`, `BenefitDetailApiTest`, `BenefitSearchApiTest`, `BookmarkApiTest`, `BookmarkConcurrencyTest`, `BenefitNormalizationTest`, `CollectionServiceTest`, `CollectionFaultToleranceTest`, `MatchApiTest`, `MatchListApiTest`, `PublicMatchApiTest`, `ProfileApiTest`, `ProfileConcurrencyTest`.
   - `CollectionSchedulerTest.정기_수집은_전체_수집을_한_번_실행한다` 첫머리의 정리 반복문을 지운다.
   - `@BeforeEach`의 `DELETE FROM benefit WHERE service_id = ?`(`WithdrawalApiTest`, `BookmarkConcurrencyTest`)를 지운다.
   - stub 초기화(`stub.reset()`, `removeService`), `mailSender.clear()`, 테스트 본문 안의 부분 삭제는 남긴다.
   - 지운 뒤 쓰이지 않게 된 `JdbcTemplate` 필드·import를 정리한다. 단언에 쓰는 곳이 있으면 남긴다.
7. **공유 상태 검증 테스트** `backend/src/test/java/kr/govbenefit/SharedTestDatabaseTest.java`
   - `@SpringBootTest(properties = "gov24.schedule.enabled=false")`, `@Import(TestcontainersConfiguration.class)`.
   - `컨테이너는_bean이_아니라_컨텍스트를_닫아도_닫히지_않는다`: `applicationContext.getBeansOfType(Startable.class)`가 비어 있는지 본다. 설계 1이 깨지면 바로 실패한다.
   - `테스트마다_시드를_뺀_테이블이_비어_있다`: `information_schema`의 테이블 중 `flyway_schema_history`·`region`을 뺀 모든 테이블의 행 수가 0이고, `region`은 비어 있지 않은지 본다. 앞선 테스트가 무엇을 남겼든 공통 정리가 돌았는지 확인한다.
8. **결정 문서** `docs/decisions/0032-test-shared-mysql.md`(템플릿 사용)
   - 결정: 테스트 JVM 하나에 MySQL 컨테이너 하나, 컨테이너는 bean으로 두지 않는다. 모든 Spring 테스트는 메서드 전에 시드를 뺀 테이블을 공통으로 비운다. 고정 시계가 필요한 테스트는 `FixedClockConfiguration`을 `@Import`하고 `@BeforeEach`에서 시각을 맞춘다.
   - 영향: 새 테스트는 자체 전체 정리를 두지 않는다. 마이그레이션으로 시드를 넣는 테이블을 더하면 `DatabaseCleanupListener.KEEP_TABLES`에 더한다. 테스트 메서드 사이에 데이터를 이어 쓰지 않는다.
   - `docs/decisions/README.md` 결정 목록 표에 0032를 더한다.
9. **마무리**: 검증 명령을 통과시키고 PR 설명에 2단계 기준값과 바꾼 뒤 값을 함께 적는다. PR 직전 마지막 커밋에서 `status`를 `done`으로 바꾼다.

## 완료 조건 매핑

| 완료 조건 | 구현 단계 | 확인 방법 |
| --- | --- | --- |
| `./gradlew test` 한 번에 MySQL 컨테이너가 하나만 뜬다(실행 중 동시에 떠 있는 MySQL 컨테이너 수로 확인한다). | 3 | 검증 명령 1의 최대 동시 개수가 1 |
| 고정 시계를 쓰는 테스트 중 다른 설정이 같은 것들은 컨텍스트 하나를 공유한다. 전체 테스트 컨텍스트 수가 바꾸기 전보다 줄어든다. | 5 | 검증 명령 2. 시작 로그의 6개 클래스(`SignUp`·`EmailVerification`·`PasswordReset`·`RefreshToken`·`Withdrawal`·`Profile` ApiTest)가 컨텍스트 하나로 시작되고, 전체 수가 2단계 기준값보다 작음 |
| 테스트 도중 컨텍스트를 닫는 테스트가 있어도 그 뒤의 테스트가 DB에 정상적으로 연결한다. | 3, 7 | `SharedTestDatabaseTest.컨테이너는_bean이_아니라_컨텍스트를_닫아도_닫히지_않는다`, 검증 명령 3(캐시 크기 1로 매번 컨텍스트를 닫는 실행) 통과 |
| 같은 DB나 같은 시계를 쓰게 된 테스트끼리 데이터나 시각이 섞여 결과가 달라지지 않는다. 순서를 바꿔 실행해도 통과한다. | 4, 5, 6, 7 | `SharedTestDatabaseTest.테스트마다_시드를_뺀_테이블이_비어_있다`, 검증 명령 4(클래스·메서드 순서 무작위) 3회 통과 |
| CI의 `test` job이 취소 없이 통과하고, 바꾸기 전보다 실행 시간이 늘지 않는다. | 전체 | PR의 `test` job 성공. 소요 시간이 최근 develop 대상 PR의 7~9분 이하 |

## 검증 명령

모두 `backend/`에서 실행한다(Docker 필요).

1. 컨테이너 수: 테스트를 돌리는 동안 다른 터미널에서 1초마다 표본을 뜬다. 다른 워크트리의 MySQL과 섞이지 않도록 테스트 외 MySQL 컨테이너는 끈 상태에서 잰다.
   ```sh
   max=0; while true; do n=$(docker ps -q --filter label=org.testcontainers=true --filter ancestor=mysql:8.4 | wc -l); [ "$n" -gt "$max" ] && max=$n && echo "max=$max"; sleep 1; done
   ```
   메모리 합계는 같은 필터로 `docker stats --no-stream`을 함께 본다.
2. 컨텍스트 수: `./gradlew test` 후 `grep -ho 'Started [A-Za-z]* in' build/test-results/test/*.xml | sort | uniq -c`. 컨텍스트가 하나 시작될 때마다 Spring Boot 시작 로그가 한 줄 남는다.
3. 컨텍스트 강제 종료: `JAVA_TOOL_OPTIONS='-Dspring.test.context.cache.maxSize=1' ./gradlew test --no-daemon --rerun-tasks`. 다른 컨텍스트로 넘어갈 때마다 이전 컨텍스트가 닫힌다.
4. 순서 무작위:
   ```sh
   JAVA_TOOL_OPTIONS='-Djunit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$Random -Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random' ./gradlew test --no-daemon --rerun-tasks
   ```
   3회 실행한다. 실패하면 로그에 찍힌 seed를 `junit.jupiter.execution.order.random.seed`로 넘겨 재현한다.
5. 기본 검증: `./gradlew test`.

## 결정 필요

없음
