status: in-progress

# 구현 계획 — #113 테스트 컨텍스트가 늘어도 테스트 MySQL 연결 한도를 넘지 않게 조정

브랜치: `test/113-test-db-connection-limit` (develop `29a34a1` 기준)

## 목표 및 범위

- 목표: 테스트 클래스를 새로 만들어 Spring 테스트 컨텍스트가 늘어도 테스트 MySQL의 연결 한도를 넘지 않게 한다. 전체 테스트 실행 중 최대 동시 연결 수와 한도까지 남은 여유를 측정해 남긴다.
- 포함:
  - 테스트 전용 설정(`backend/src/test/resources/config/application.yaml`)의 커넥션 풀 설정.
  - 바꾸기 전후 최대 동시 연결 수 측정과 #107 실패 상황 재현.
  - 테스트 커넥션 풀 규칙을 결정 문서로 올린다.
- 제외:
  - 운영·로컬 실행의 커넥션 풀 설정(`src/main/resources`). 이슈 완료 조건으로 바꾸지 않는다.
  - 테스트 MySQL의 연결 한도(`max_connections`) 변경. 설계 결정 1 참고.
  - 테스트 컨텍스트 합치기, 컨텍스트 캐시 크기 변경, 테스트 병렬 실행.
  - 동시성 테스트의 스레드 수 변경.

## 확인된 사실과 제약

- 커넥션 풀 설정
  - 저장소 어디에도 `spring.datasource.hikari.*` 설정이 없다. 테스트도 HikariCP 기본값을 쓴다.
  - 테스트 런타임의 HikariCP는 7.0.2다(`./gradlew dependencyInsight --dependency HikariCP --configuration testRuntimeClasspath`).
  - HikariCP 7.0.2 기본값(`HikariConfig` 소스로 확인): `maximumPoolSize` 10, `minimumIdle`은 지정하지 않으면 `maximumPoolSize`와 같다. `idleTimeout`은 10분이다.
  - 유휴 연결 정리(`HikariPool.HouseKeeper`)는 `minimumIdle < maximumPoolSize`일 때만 돈다. 주기는 30초(`com.zaxxer.hikari.housekeeping.periodMs`)다. 기본값에서는 최소 유휴 수와 최대 크기가 같아서, 풀이 시작하면 연결을 10개 채우고 유휴 연결을 닫지 않는다.
  - `idleTimeout`이 0이 아니고 10초보다 작으면 경고 후 기본값 10분으로 되돌린다(`HikariConfig.validateNumerics`). 10초로 줄이려면 정확히 `10000`을 줘야 한다.
- 테스트 컨텍스트
  - `@SpringBootTest` 클래스는 42개이고, 모두 같은 MySQL 컨테이너에 연결한다(결정 0033). 이슈 본문 기준으로 `./gradlew test`에서 컨텍스트가 18개 뜬다.
  - Spring은 실행이 끝난 컨텍스트를 캐시(기본 32개, LRU)에 남기고, 캐시에 남은 컨텍스트의 `DataSource`도 열린 채로 둔다. 테스트는 한 번에 하나씩 돌므로(결정 0033) 연결을 실제로 쓰는 컨텍스트는 한 번에 하나다.
  - 결국 동시 연결 수는 사용량과 상관없이 컨텍스트 수 × 10에 비례해 늘어난다.
- 테스트 MySQL
  - `mysql:8.4`이고, 연결 한도는 기본값 151이다. `TestcontainersConfiguration`에 한도 설정이 없다.
  - 앱은 `MySQLContainer` 기본 계정 `test`로 접속한다. 이 계정에는 `CONNECTION_ADMIN`이 없어서 관리자용 추가 연결 1개를 쓰지 못한다.
- 동시성 테스트는 스레드를 여러 개 띄워 연결을 동시에 쓴다. `SignUpConcurrencyTest`·`RefreshTokenConcurrencyTest`·`BookmarkConcurrencyTest`·`ProfileConcurrencyTest`는 8개, `LoginAttemptConcurrencyTest`는 20개, `WithdrawalConcurrencyTest`는 2개를 띄운다. `EmailVerificationConcurrencyTest`는 테스트마다 스레드 수를 넘긴다. 최대 풀 크기를 8보다 작게 줄이면 요청이 풀에서 줄을 서서, 경합을 만들지 못한 채 통과할 수 있다.
- 테스트 전용 설정 `backend/src/test/resources/config/application.yaml`은 테스트 클래스패스에만 있어서 운영·로컬 실행에 영향이 없다.
- `ActuatorEndpointTest`는 `hikaricp_connections_active{` 지표가 노출되는지만 확인한다. 풀 크기 값에는 기대지 않는다.
- #107 작업 중 테스트 클래스를 하나 새로 만들어 컨텍스트가 하나 늘자, `PublicMatchApiTest`가 `Too many connections`로 컨텍스트를 띄우지 못해 테스트 9개가 실패했다. #107은 새 테스트를 기존 클래스(`CollectionServiceTest`)에 넣어 이 문제를 피했다([#107 플랜](107-new-benefit-detection.md)).
- 다음 결정 번호는 0043이다.

## 설계 결정

1. **테스트에서만 최소 유휴 연결 수를 1로, 유휴 연결 정리 시간을 10초로 줄인다. 최대 풀 크기는 기본값 10을 그대로 둔다.** 결정 문서 0043으로 올린다.
   - 캐시에 남은 컨텍스트는 연결을 1\~2개만 남기고, 나머지는 쉬기 시작한 뒤 10\~40초 안에 닫는다(유휴 정리 10초 + 정리 주기 30초). 연결을 쓰는 컨텍스트는 필요한 만큼 최대 10개까지 늘린다.
   - 최대 풀 크기를 그대로 두므로 동시성 테스트가 동시에 쓰는 연결 수는 바뀌지 않는다.
   - `minimum-idle`을 0이 아니라 1로 두는 이유: 연결 하나를 미리 열어 두어, 쉬던 컨텍스트로 돌아왔을 때 새 연결을 기다리지 않는다. 컨텍스트마다 1\~2개 차이라 한도에는 영향이 작다.
   - `idle-timeout`은 HikariCP가 허용하는 최솟값 10초(`10000`)다. 10초보다 작게 주면 10분으로 되돌아간다.
   - 제외한 대안 1, 최대 풀 크기를 줄이기(예: 3): 동시성 테스트(스레드 8개)가 풀에서 줄을 서 경합을 확인하지 못할 수 있다.
   - 제외한 대안 2, 테스트 MySQL의 `--max-connections`를 올리기: 한 줄로 끝나지만, 연결 수가 컨텍스트 수 × 10으로 계속 늘어나는 구조는 그대로라 한계를 뒤로 미룰 뿐이다.
   - 제외한 대안 3, 컨텍스트 캐시 크기(`spring.test.context.cache.maxSize`)를 줄이기: 캐시에서 밀려난 컨텍스트를 다시 띄우게 되어 테스트 시간이 늘어난다.

## 단계별 구현 계획

1. **플랜 상태**: 착수할 때 이 파일의 `status`를 `in-progress`로 바꾼다.
2. **바꾸기 전 기준값 측정** (develop `29a34a1` 상태)
   - 검증 명령 1로 `./gradlew test` 실행 중 최대 동시 연결 수(`Max_used_connections`)를 잰다.
   - 검증 명령 2로 컨텍스트 수를 센다.
   - `./gradlew test` 소요 시간을 잰다.
3. **바꾸기 전 실패 재현**
   - 검증 명령 3의 임시 테스트 클래스 1개를 더해 `./gradlew test`를 돌리고, `Too many connections`로 실패하는지 확인한다.
   - 임시 클래스는 지우고 커밋하지 않는다.
4. **테스트 커넥션 풀 설정** `backend/src/test/resources/config/application.yaml`
   - `spring.datasource.hikari.minimum-idle: 1`, `spring.datasource.hikari.idle-timeout: 10000`을 더한다(설계 1).
   - 주석에 이유(캐시에 남은 컨텍스트가 연결을 쥐지 않게 함, 최대 크기는 동시성 테스트 때문에 유지, 10초 미만은 10분으로 되돌아감)와 결정 0043을 적는다.
5. **바꾼 뒤 측정과 재현**
   - 검증 명령 1·2로 최대 동시 연결 수와 컨텍스트 수를 다시 잰다. 소요 시간도 잰다.
   - 검증 명령 3의 임시 클래스 1개를 더해 통과하는지 확인한다(#107 상황).
   - 임시 클래스 20개를 더해 통과하는지, 그때 최대 동시 연결 수가 얼마인지 잰다(여유 확인).
   - 임시 클래스는 지우고 커밋하지 않는다.
6. **결정 문서** `docs/decisions/0043-test-connection-pool.md`(템플릿 사용)
   - 결정: 테스트 커넥션 풀은 최소 유휴 연결 수 1, 유휴 연결 정리 10초로 둔다. 최대 크기는 줄이지 않는다. 테스트 클래스에서 풀 설정을 덮지 않는다.
   - 근거에 2·5단계 측정값과 제외한 대안을 적는다.
   - 영향: 새 테스트 클래스는 따로 신경 쓰지 않아도 된다. 최대 풀 크기를 줄이려면 동시성 테스트의 스레드 수를 함께 검토하고 이 결정을 대체한다.
   - `docs/decisions/README.md` 결정 목록 표에 0043을 더한다.
7. **마무리**: 검증 명령 4를 통과시키고, PR 설명에 2·5단계 측정값을 적는다. PR 직전 마지막 커밋에서 `status`를 `done`으로 바꾼다.

## 완료 조건 매핑

| 완료 조건 | 구현 단계 | 확인 방법 |
| --- | --- | --- |
| #107에서 실패한 상황(새 테스트 클래스 추가로 컨텍스트가 하나 늘어남)을 재현해도 `Too many connections`로 실패하지 않는다. | 3, 4, 5 | 검증 명령 3. 바꾸기 전에는 임시 클래스 1개로 실패하고, 바꾼 뒤에는 1개와 20개 모두 통과 |
| 전체 테스트를 실행하는 동안의 최대 동시 연결 수와, 연결 한도까지 남은 여유를 측정해 남긴다. | 2, 5, 6 | 검증 명령 1. 바꾸기 전후 `Max_used_connections`와 `151 - Max_used_connections`를 PR 설명과 결정 문서 0043에 기록 |
| 운영·로컬 실행의 커넥션 풀 설정은 바뀌지 않는다. | 4 | `git diff develop --stat -- backend/src/main`이 비어 있음. 바꾼 파일은 테스트 리소스뿐 |

## 검증 명령

모두 `backend/`에서 실행한다(Docker 필요).

1. 최대 동시 연결 수: `./gradlew cleanTest test`를 돌리는 동안 다른 터미널에서 아래 스크립트를 실행한다. 1초마다 그 실행이 띄운 MySQL의 `Max_used_connections`(서버 시작 이후 최대 동시 연결 수)를 읽는다. 계정 `root`의 비밀번호는 `MySQLContainer` 기본값 `test`다.
   - 다른 워크트리의 테스트도 같은 이미지로 컨테이너를 띄운다. 그래서 스크립트를 시작할 때 이미 있던 컨테이너는 빼고, 처음 새로 생긴 컨테이너에 고정한다. 끝난 뒤 테스트 로그의 `Container mysql:8.4 is starting: <id>`와 고정한 ID가 같은지 확인한다.
   - 컨테이너는 테스트 JVM이 끝나면 지워지므로, 마지막으로 찍힌 값이 그 실행의 최대치다.
   - 값에는 측정용 관리자 연결이 최대 1개 들어 있다. 한도에 닿으면 관리자 계정에만 추가 연결 1개가 허용되므로 152까지 찍힐 수 있다.
   ```sh
   pre=$(docker ps -q --filter label=org.testcontainers=true --filter ancestor=mysql:8.4 | tr '\n' ' '); id=""
   while true; do
     if [ -z "$id" ]; then
       for c in $(docker ps -q --filter label=org.testcontainers=true --filter ancestor=mysql:8.4); do
         case " $pre " in *" $c "*) ;; *) id=$c; echo "pinned $id"; break;; esac
       done
     fi
     [ -n "$id" ] && docker exec "$id" mysql -uroot -ptest -N -s -e "SHOW GLOBAL STATUS LIKE 'Max_used_connections'" 2>/dev/null | sed "s/^/$(date +%T) /"
     sleep 1
   done
   ```
2. 컨텍스트 수: `./gradlew test` 후 `grep -ho 'Started [A-Za-z0-9]* in' build/test-results/test/*.xml | wc -l`. 컨텍스트가 하나 시작될 때마다 Spring Boot 시작 로그가 한 줄 남는다.
3. 컨텍스트 추가 재현: 다른 설정과 겹치지 않는 프로퍼티로 컨텍스트를 하나씩 새로 띄우는 임시 테스트 클래스를 만든다. 20개가 필요하면 `probe.context` 값과 클래스 이름만 바꿔 복사한다.
   ```java
   package kr.govbenefit;

   @SpringBootTest(properties = {"gov24.schedule.enabled=false", "probe.context=1"})
   @Import(TestcontainersConfiguration.class)
   class ContextProbe1Test {
       @Autowired JdbcTemplate jdbcTemplate;

       @Test
       void 연결한다() {
           assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
       }
   }
   ```
   `./gradlew cleanTest test` 후 `grep -l 'Too many connections' build/test-results/test/*.xml`로 실패한 클래스를 찾는다.
4. 기본 검증: `./gradlew test`.

## 결정 필요

없음
