# 0033. 테스트 MySQL 컨테이너 공유와 테이블 정리

- 상태: 채택
- 날짜: 2026-10-27
- 출처: #85, [플랜](../plans/85-shared-test-mysql.md)

## 배경

테스트용 MySQL 컨테이너는 `TestcontainersConfiguration`의 bean이라 Spring 테스트 컨텍스트마다 하나씩 떴고, 컨텍스트 캐시 때문에 테스트가 끝날 때까지 닫히지 않았다. 설정이 조금씩 다른 테스트 클래스가 늘면서 `./gradlew test` 한 번에 컨테이너가 20개 넘게 떴다. 로컬에서는 최대 19개가 동시에 떠 메모리를 약 7.2GiB까지 썼다. CI의 `test` job은 테스트 도중 취소됐고(#78), 로컬에서도 컨테이너가 죽어 DB 연결이 실패했다.

컨테이너를 하나로 합치면 모든 테스트가 DB 하나를 함께 쓴다. 지금까지는 클래스마다 지우는 테이블이 달라도 컨텍스트마다 DB가 따로라 문제가 없었다.

## 결정

테스트 JVM 하나에 MySQL 컨테이너 하나를 띄우고 모든 테스트 컨텍스트가 함께 쓴다. 모든 Spring 테스트는 테스트 메서드마다 시드를 뺀 모든 테이블을 공통으로 비우고 시작한다.

- 컨테이너는 static으로 한 번 띄우고 bean으로 등록하지 않는다. 접속 정보는 `DynamicPropertyRegistrar` bean으로 넘긴다. 컨테이너는 JVM이 끝날 때 Testcontainers가 지운다.
- 정리는 `DatabaseCleanupListener`가 맡는다. `META-INF/spring.factories`로 모든 Spring 테스트에 등록되고, 테스트 클래스의 `@BeforeEach`보다 먼저 돈다. `flyway_schema_history`와 `KEEP_TABLES`의 시드 테이블(`region`)을 뺀 모든 테이블을 FK 검사를 끄고 `DELETE`한다.
- 고정 시계가 필요한 테스트는 `FixedClockConfiguration`을 `@Import`하고 `@BeforeEach`에서 `MutableClock.set`으로 자기 기준 시각을 맞춘다. 클래스마다 시계 설정을 따로 두지 않는다.

## 근거

- 컨테이너가 bean이면 컨텍스트를 닫을 때 함께 닫힌다. Spring Boot 4.1.1 `TestcontainersLifecycleBeanPostProcessor`가 `Startable` bean을 `close()`한다. destroy 메서드가 지정된 bean은 프레임워크가 `close()`를 부른다. `@ImportTestcontainers`의 static 필드도 bean 정의로 등록된다. 컨텍스트 캐시가 가득 차거나 `@DirtiesContext`로 컨텍스트 하나가 닫히면, 공유 컨테이너를 쓰는 다른 컨텍스트의 연결이 끊긴다.
- 제외한 대안
  - reuse 컨테이너: `~/.testcontainers.properties` 같은 실행 환경 설정에 기대므로 CI와 로컬의 동작이 달라진다.
  - `jdbc:tc:` URL: 시작 명령과 `TZ` 환경 변수를 코드로 둘 수 없다.
- 정리를 한 곳에 둔 이유: 클래스별 정리는 범위가 제각각이었다. DB를 공유하면 빠뜨린 테이블 때문에 다른 클래스의 결과가 달라지고, FK 때문에 삭제가 실패한다(`bookmark.benefit_id`에는 `ON DELETE CASCADE`가 없다). 테이블 목록을 `information_schema`에서 읽으므로 새 테이블도 자동으로 비워진다.
- `TRUNCATE`가 아니라 `DELETE`를 쓰는 이유: `TRUNCATE`는 테이블을 다시 만드는 DDL이라 테스트 메서드마다 쓰면 느려진다. 비어 있는 테이블의 `DELETE`는 거의 비용이 없다.
- 시계 설정을 하나로 둔 이유: 클래스마다 nested 설정을 두면 내용이 같아도 컨텍스트를 공유하지 못한다.

## 적용 위치

- `backend/src/test/java/kr/govbenefit/TestcontainersConfiguration.java`
- `backend/src/test/java/kr/govbenefit/DatabaseCleanupListener.java`, `backend/src/test/resources/META-INF/spring.factories`
- `backend/src/test/java/kr/govbenefit/FixedClockConfiguration.java`
- `backend/src/test/java/kr/govbenefit/SharedTestDatabaseTest.java`: 컨테이너가 bean이 아닌지, 테스트 시작 시 시드를 뺀 테이블이 비어 있는지 확인한다.

## 영향

- 새 테스트는 `@BeforeEach`에서 테이블 전체를 지우지 않는다. 테스트 메서드 사이에 데이터를 이어 쓰지 않는다.
- 마이그레이션으로 데이터를 넣는 테이블을 더하면 `DatabaseCleanupListener.KEEP_TABLES`에 더한다.
- 테스트 JVM을 여러 개로 나누거나(`maxParallelForks`) 테스트를 병렬로 돌리려면 이 결정을 대체해야 한다. 지금 정리 방식은 테스트가 한 번에 하나씩 돈다는 것을 전제로 한다.
- 컨테이너 설정(이미지, 문자 집합, 정렬 규칙, 시간대)은 [결정 0001](0001-db-collation.md), [결정 0002](0002-db-timezone.md)를 따른다.
