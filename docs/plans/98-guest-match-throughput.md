status: in-progress

# 구현 계획 — #98 비회원 조건 비교 목록의 처리량 개선

브랜치: `improve/98-guest-match-throughput` (develop `d6e6523` 기준)

## 목표 및 범위

- 목표: 조건 비교 목록(비회원 `POST /api/matches`, 로그인 `GET /api/me/matches`)이 요청마다 혜택 전체와 지원조건 원문 전체를 읽고 파싱하지 않게 해 처리량 한계를 올린다. 결과(건수·판정·순서)는 그대로다. #96과 같은 조건으로 다시 재서 측정 기록 형식으로 남긴다.
- 포함:
  - 원인 확인: 로컬 실데이터로 요청 1건의 비용을 나눠 잰다. 대안 A(필요한 컬럼만 읽기)도 로컬에서 재서 비교한다.
  - 판정 입력의 메모리 스냅샷과, 수집 실행이 끝날 때의 교체.
  - 목록 판정을 트랜잭션 밖으로 옮겨 판정하는 동안 커넥션을 쥐지 않게 한다.
  - 결정 문서 0038, `DEC-FRESHNESS`의 조건 비교 목록 허용 지연, API 설명 한 줄.
  - `run.sh`의 결과 디렉터리 환경 변수.
  - 운영 측정(조건 비교, 혼합)과 보고서.
- 제외:
  - 상세 비교(`/{serviceId}`)와 검색·상세 조회. 지금처럼 요청마다 DB를 읽는다.
  - #96에서 확인한 검색(db CPU)과 로그인(BCrypt) 병목. 별도 이슈로 다룬다.
  - `ConditionMatcher`의 판정 비용 최적화. 측정에서 필요하면 제안으로 남긴다.
  - 실데이터로 개선 전후 목록을 대조하는 스크립트. 테스트로만 확인한다(2026-11-01 사용자 결정).
  - 앱을 여러 대로 늘리는 구성.

## 확인된 사실과 제약

- `MatchService.list(MatchSubject, …)`는 요청마다 `regionService.regions()`(243행), `sourceRecordRepository.findAllByEndpoint(SUPPORT_CONDITIONS)`(1.1만 건), `benefitRepository.findAll(SEARCH_ORDER)`(1.1만 건, 엔티티 전체 컬럼)를 읽는다. 원문마다 `jsonMapper.readTree`로 파싱하고, 해당·확인 필요 전부에 `MatchListItem.of`를 만든 뒤 쪽을 자른다.
- #96 지표(`results/20261031T204014-guest-match/prometheus.json`)
  - app VM이 받은 양은 초당 1건에서 25.1MB/s, 8건에서 199MB/s다(`vm_network_received`). 요청 1건에 약 25MB다.
  - 초당 8건에서 app CPU 92%, db CPU 70%, 힙 최대 2,147MiB, GC 정지 24%, 풀 활성 평균 8.9였다.
- 로컬 실데이터(혜택 10,950건) 크기, 2026-11-01 확인
  - benefit 전체 컬럼 12.05MB. 그중 TEXT 4개(`target`·`selection_criteria`·`support_content`·`phone_inquiry`)가 7.35MB다.
  - 목록 항목과 판정에 필요한 컬럼은 2.51MB, 판정 컬럼(`service_id`·`user_type`·`agency_type`·`agency_code`)만은 0.40MB다.
  - 지원조건 원문은 8.74MB다. 목록·상세 원문(15.78MB, 18.44MB)은 보내지 않지만 같은 `source_record` 테이블에 있다.
- 사전 확인(2026-11-01, 저장소 밖의 임시 코드)
  - 같은 쿼리를 JDBC로 실행(로컬, 15회 평균, 엔티티 적재 제외): benefit 전체 컬럼 110ms, 필요한 컬럼 34ms, 판정 컬럼 9ms, 지원조건 원문 읽기 37ms, 읽기와 파싱 64ms.
  - 빌드한 `ConditionMatcher`로 합성 데이터 1.1만 건만 판정(로컬 코어 하나): 요청 1건 약 5.7ms. 그중 `PersonalTarget.judge`의 `split`이 약 1ms다.
- `benefit.name`과 `source_record.endpoint`에는 인덱스가 없다. `source_record`의 유니크 키는 `(service_id, endpoint)`다.
- 트랜잭션과 커넥션
  - `MatchService`는 클래스 단위 `@Transactional(readOnly = true)`다. OSIV는 꺼져 있다.
  - Spring 7.0.9 `HibernateJpaDialect.beginTransaction`은 readOnly 트랜잭션을 시작할 때 커넥션을 먼저 잡고(`DELAYED_ACQUISITION_AND_HOLD`), 끝날 때까지 쥔다. 쿼리가 없어도 같다.
  - `UserProfile.conditionCodes`는 지연 로딩 `@ElementCollection`이다. 트랜잭션 밖에서 `lifeAttributes()`를 부르면 `LazyInitializationException`이 난다.
  - 비회원 프로필 검증(`ProfileValidator` → `RegionCodeValidator`)은 시도·시군구를 `regionRepository.findById`로 최대 2번 읽는다.
- 쓰기 경로
  - `benefit`·`source_record`를 쓰는 곳은 수집(`CollectionPageWriter.write`, 쪽마다 `@Transactional`)뿐이다.
  - 수집은 `CollectionScheduler`(매일 04:00)와 `collect` 프로필의 `CollectionRunner`가 `CollectionService.collectAll()`로 시작한다. `collectAll()`은 트랜잭션 밖이다.
  - `CollectionScheduler`는 단일 인스턴스를 전제한다.
  - 조사 때 전체 수집의 API 호출은 29초였다(데이터 검증 보고서). 운영 소요 시간은 `collection_last_run_duration_seconds`로 본다.
- 선례
  - 이벤트: `SignupCodeIssued`·`PasswordResetIssued`(record)를 `ApplicationEventPublisher`로 내고 `AccountMailListener`가 받는다.
  - 메모리 상태: `LoginAttemptLimiter.clear()`를 테스트가 메서드마다 부른다. `LoginAttemptResetListener`가 `META-INF/spring.factories`에 `DatabaseCleanupListener`와 함께 등록돼 있다.
- 테스트
  - `MatchListApiTest`(17개)·`PublicMatchApiTest`(9개)·`MatchApiTest`는 데이터를 stub 수집(`collectionService.collectAll()`)으로만 넣고 DB를 직접 고치지 않는다.
  - `PublicMatchApiTest.같은_프로필_값이면_로그인_사용자의_목록_상세와_응답이_같다`가 비회원과 로그인 목록이 같은지 본다.
- 요구사항과 결정
  - `REQ-QUALITY-02`(캐시 사용 시 정확성, P1)의 네 조건을 따라야 한다.
  - `DEC-FRESHNESS`의 "캐시의 허용 지연"은 아직 결정할 사항이다.
  - 결정 0008의 "지원조건 코드는 원문으로만 둔다"는 DB 저장 규칙이다. `MatchService`·`SupportConditions` 주석에는 "조회할 때마다 원문을 읽는다"고 적혀 있다.
- 측정
  - `run.sh`는 결과를 `docs/research/load-test-baseline/results/`에 쓴다(고정).
  - 개선 전후는 같은 스크립트·판정 기준으로 재고, 같은 입력은 `INPUTS`로 준다(`docs/ops/load-test.md`).
  - `scripts/deploy.sh`는 서비스 이름 없이 `docker compose up -d`를 실행해 멈춘 `cloudflared`를 다시 띄운다.
  - 브랜치 이미지는 `gh workflow run image.yml --ref <브랜치>`로 `sha-<7자리>` 태그로 만든다. 운영은 지금 `v0.1.1`이다.
- 실행 이미지는 Java 21이고 가상 스레드를 쓰지 않는다.

## 설계 결정

1. **판정 입력을 메모리 스냅샷으로 두고, 수집 실행이 끝나면 교체한다.** → 결정 문서 0038(2026-11-01 사용자 결정)
   - 스냅샷은 혜택마다 `MatchCandidate(BenefitSummary summary, String userType, AgencyRegion agencyRegion, SupportConditions conditions)`이고, `SEARCH_ORDER`로 DB가 정렬한 순서의 불변 `List`다. 프로필·나이·판정 결과는 담지 않는다(`REQ-QUALITY-02` 사용자별 데이터).
   - 만드는 쿼리와 파싱은 지금 `list()` 앞부분을 그대로 옮긴다. 실행 시점만 바뀌므로, 수집과 수집 사이의 결과가 지금과 같다. 판정은 요청마다 같은 `ConditionMatcher.match`를 부른다.
   - **교체**
     - `collectAll()`이 끝날 때 `CollectionRunFinished`를 내고, 리스너가 같은 스레드에서 새로 만들어 참조만 바꾼다.
     - 성공·부분 실패·실패를 가리지 않는다. 쪽마다 커밋하므로 실패한 실행도 일부 쪽은 바뀌었을 수 있다.
     - 만드는 동안 요청은 이전 스냅샷을 쓴다.
   - **비어 있을 때**(기동 직후, 교체 실패 뒤): 첫 요청이 만들고, 동시에 온 요청은 기다린다(`REQ-QUALITY-02` 대기 정책). 기동할 때 미리 만들지 않는다.
   - **교체 실패**: 비우고 예외를 그대로 던진다. 다음 요청이 다시 만든다.
   - **늦게 끝난 생성**: 생성과 교체를 같은 잠금 안에서 한다. 수집 중에 시작한 생성이 늦게 끝나도 교체가 그 뒤에 돌아 덮는다(`REQ-QUALITY-02` 늦은 조회).
   - **두지 않는 것**: TTL, 버전 확인, 끄는 설정. 데이터를 바꾸는 곳이 수집뿐이고 앱이 한 대다. 앱 안의 메모리라 캐시만 따로 꺼지는 경우가 없다.
   - 고르지 않은 대안(보고서 5절에 비교를 남긴다)
     - A. 필요한 컬럼만 읽는다. 지원조건 읽기·파싱이 남는다.
     - C. 수집 때 비교 코드를 컬럼으로 저장한다. 0008을 대체하고 기존 행을 채워야 하며, 요청마다 1.1만 행을 읽고 정렬하는 비용은 남는다.
     - D. 결과 캐시, 자원 조정, 동시 실행 제한. 적중률이 낮거나 한계 자체가 오르지 않는다.
2. **허용 지연: 조건 비교 목록은 마지막으로 끝난 수집 기준이다.** → `DEC-FRESHNESS`(2026-11-01 사용자 결정)
   - 수집이 도는 동안부터 교체가 끝날 때까지는 직전 수집 기준이다. 만료 시간은 없다. 교체에 실패하면 비우고, 다음 요청이 DB에서 다시 만든다.
   - 상세 비교와 검색은 DB를 바로 읽으므로, 그 사이에는 목록과 다를 수 있다.
   - 대안: 수집 중에는 DB에서 매번 읽는다. 수집 상태를 알아야 하고, 수집 중 반쯤 바뀐 데이터를 읽는 것은 지금과 같다.
3. **목록 판정은 트랜잭션 밖에서 한다.**
   - `list`·`listWith`에 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`을 붙인다. 계정 확인, 프로필 조회, 지역 코드 조회는 리포지토리 호출마다 짧게 끝난다.
   - 로그인 목록의 프로필은 `@EntityGraph(attributePaths = "conditionCodes")`를 붙인 `UserProfileRepository.findWithConditionCodesByUserId`로 읽는다. 그러면 트랜잭션 밖에서도 `MatchSubject`를 만들 수 있다.
   - 트랜잭션을 그대로 두면 판정하는 동안(수 ms) 커넥션을 쥔다. CPU가 차서 판정이 늘어지면 점유 시간도 늘어난다(#96 로그인과 같은 현상).
   - 대안: 프로필 읽기 전용 빈을 따로 둔다(클래스가 하나 는다). `TransactionTemplate`을 쓴다(선언적·프로그래밍 방식 트랜잭션이 한 클래스에 섞인다).
4. **목록 항목은 지금처럼 판정할 때 만든다.**
   - `BenefitSummary`는 스냅샷에 있으므로 항목을 만들 때 새로 만들지 않는다. `MatchListItem.of`는 `Benefit` 대신 `BenefitSummary`를 받는다.
   - 대안: 쪽에 들어갈 항목만 만든다. 판정 결과를 담는 타입이 하나 늘고, 아끼는 비용(항목당 작은 객체 8개)은 판정 비용보다 작다(추정). 측정에서 필요하면 제안으로 남긴다.
5. **측정: 원인 분해와 대안 A는 로컬에서 재고, 운영은 마지막에 한 번 잰다.**(2026-11-01 사용자 결정)
   - **로컬**
     - 실데이터(로컬 DB)로 앱을 띄우고 JFR을 켠 채, k6 `guest-match.js`를 #96의 `inputs.json`으로 보낸다.
     - 요청 지연(k6 p50·p95)과 JFR(`jfr view hot-methods`, `allocation-by-site`)로 JDBC, 엔티티 적재, JSON 파싱, 판정의 몫을 나눈다. 두 쿼리의 `EXPLAIN ANALYZE`도 남긴다.
     - 같은 방식으로 (가) 지금, (나) 대안 A(benefit을 필요한 컬럼만 읽음), (다) 스냅샷을 잰다. (나)는 측정할 때만 바꾸고 커밋하지 않는다. 바꾼 쿼리는 보고서에 적는다.
   - **운영**
     - 머지 전에 브랜치 이미지로 조건 비교와 혼합을 한 번씩 잰다. 혼합은 이슈 배경(가벼운 경로까지 느려짐)이 풀렸는지 보이기 위해 잰다(2026-11-01 사용자 결정).
     - 자동 단계는 시작 속도부터 두 배씩 올리므로, 기준 측정의 단계(조건 비교 1·2·4·8, 혼합 2·4·8·16·32)를 앞부분에 포함한다.
     - API를 막은 뒤 app VM에서 `APP_TAG`만 바꿔 `docker compose up -d app`으로 app만 다시 만든다. 끝나면 같은 방식으로 `v0.1.1`로 되돌린 뒤 API를 푼다. `deploy.sh`는 `cloudflared`를 다시 띄우므로 쓰지 않는다.
   - 대안: 단계마다 운영에서 잰다. 배포되지 않을 상태를 재게 되고, 회마다 API를 50분 안팎 막는다. 작은 차이는 측정 해상도(25% 간격, 날짜 간 흔들림 미확인, 클럭 변동) 때문에 구분하기 어렵다.
6. **결과 디렉터리**: `run.sh`에 `RESULTS_DIR` 환경 변수를 더한다(기본값은 지금 경로). 이번 결과는 `docs/research/guest-match-throughput/results/`에 둔다.
   - 대안: 기준 측정 디렉터리에 함께 둔다. 기준 측정 보고서의 결과와 섞인다.
7. **결정 문서는 새 번호 0038로 쓰고, 0008은 대체하지 않는다.**
   - 0038에 적는 것: 스냅샷에 담는 것, 교체 시점, 비었을 때와 교체 실패, 허용 지연, 앱이 한 대라는 전제.
   - 다른 이슈가 따를 규칙
     - 혜택·지원조건을 바꾸는 새 경로는 교체를 일으킨다.
     - 목록 항목에 필드를 더하면 스냅샷에도 더한다.
     - 앱을 여러 대로 늘리면 이 결정을 대체한다.
   - 0008의 "지원조건 코드는 원문으로만 둔다"는 DB 저장 규칙이고, 스냅샷은 DB에 저장하지 않으므로 그대로 지킨다. 대신 주석의 "조회할 때마다 읽는다"는 고친다.

## 단계별 구현 계획

1. **플랜 상태**: 착수할 때 `status`를 `in-progress`로 바꾼다.
2. **원인 확인(로컬, 설계 5)**
   - 로컬 DB의 혜택 수와 마지막 수집 시각을 기록한다.
   - `SPRING_PROFILES_ACTIVE=local`에 JFR(`-XX:StartFlightRecording`)을 켜고 앱을 띄운다.
   - 로컬 k6(`grafana/k6:2.3.0`, #96 검증 명령과 같은 방식)로 `guest-match.js`를 `BASE_URL=http://host.docker.internal:8080`과 #96의 `inputs.json`으로 보낸다. 속도는 초당 1건과, 동시 처리가 생기는 속도 하나다.
   - `jfr view`로 묶어 보고, 두 쿼리의 `EXPLAIN ANALYZE`를 남긴다.
   - (나) 대안 A: `list()`가 benefit을 필요한 컬럼만 읽게 임시로 바꿔 같은 방식으로 잰다. 잰 뒤 되돌린다.
   - 결과(k6 요약, `jfr view` 출력, 실행 계획)는 `docs/research/guest-match-throughput/local/`에 둔다. JFR 원본 파일은 두지 않는다.
   - (구현 중 변경) `jfr view` 대신 `scripts/loadtest/local-profile.sh`와 `local-breakdown.py`로 잰다. 이유는 세 가지다.
     - `hot-methods`는 스택 맨 안쪽 메서드만 보여 JDBC·엔티티 적재·JSON 파싱·판정으로 나누기 어렵다.
     - JFR 할당 샘플은 초당 개수가 제한돼, 요청 사이에 쌓인 양이 다음 요청의 첫 할당(톰캣의 `Instant.now`)에 몰려 잡혔다.
     - GC·JIT 스레드의 CPU는 CPU 샘플에 들어오지 않는다.

     그래서 다음 방식으로 바꿨다.
     - 스레드 묶음별 CPU는 구간 앞뒤 스레드 덤프의 `cpu=`로 잰다.
     - 단계별 몫은 CPU 샘플의 스택을 따라 올라가며 정한다.
     - 할당은 `jdk.ThreadAllocationStatistics` 증가량의 합만 쓴다.
     - 앱은 `bootJar`로 만든 jar를 `-Xmx2304m -XX:ActiveProcessorCount=4`, 로그 INFO로 띄우고, k6는 로컬 바이너리(v2.3.0)로 보낸다.
   - (구현 중 확인) 결과는 `local/before`·`local/columns`·`local/snapshot`과 `local/explain-analyze.txt`에 뒀다. (나)의 실험 변경은 `local/columns/experiment.patch`로 남겼다.
   - (구현 중 확인) 운영과 같은 JDBC 주소(`sslMode` 없음)라 로컬 연결도 TLS였다(`Ssl_cipher` `TLS_AES_256_GCM_SHA384`). 요청 처리 CPU에서 TLS 몫은 1% 안팎이었다.
3. **수집 종료 이벤트**(설계 1)
   - `collection/domain/CollectionRunFinished.java`: `record CollectionRunFinished(Long runId, CollectionStatus status)`
   - `CollectionService.collectAll()`: `ApplicationEventPublisher`를 받아, 실행 기록을 저장하고 로그를 남긴 뒤 이벤트를 낸다.
4. **스냅샷**(설계 1·4)
   - `match/service/MatchCandidate.java`: 설계 1의 record.
   - `match/service/MatchCandidateLoader.java`: `@Transactional(readOnly = true) List<MatchCandidate> load()`. `regionService.regions()`, 지원조건 원문 파싱(`SupportConditions.parse(jsonMapper.readTree(…))`), `benefitRepository.findAll(SEARCH_ORDER)`로 목록을 만든다.
   - `match/service/MatchCandidateStore.java`
     - `List<MatchCandidate> get()`: 비어 있으면 잠금 안에서 한 번만 만든다.
     - `@EventListener void refresh(CollectionRunFinished event)`: 잠금 안에서 새로 만들어 바꾸고, 실패하면 비운다. 바꿀 때 건수와 걸린 시간을 로그로 남긴다.
     - `void clear()`: 테스트가 메서드마다 비운다.
   - `MatchService.list(MatchSubject, …)`: `candidateStore.get()`을 돌며 판정한다. 목록 경로에서 `regionService`·`sourceRecordRepository`·`benefitRepository` 조회를 뺀다(상세 비교는 그대로 쓴다).
   - `MatchListResponse.MatchListItem.of(BenefitSummary, MatchOutcome)`로 바꾼다.
5. **트랜잭션**(설계 3)
   - `MatchService.list(Long, …)`·`listWith(…)`에 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`을 붙인다.
   - `UserProfileRepository.findWithConditionCodesByUserId(Long userId)`에 `@EntityGraph(attributePaths = "conditionCodes")`를 붙이고, `MatchService.subject`가 이것으로 읽는다.
6. **주석과 API 설명**(설계 2·7)
   - `MatchService` 클래스·목록 주석과 `SupportConditions` 주석: 목록은 스냅샷을 만들 때 원문을 읽고, 상세 비교는 요청마다 읽는다.
   - `MatchController.list`·`PublicMatchController.list`의 `@Operation` 설명에 "혜택과 지원조건은 마지막으로 끝난 수집 기준이다. 수집 중에는 직전 수집 기준이다."를 더한다.
7. **테스트**
   - `src/test/java/kr/govbenefit/MatchCandidateResetListener.java`: 테스트 메서드마다 `MatchCandidateStore.clear()`를 부른다. `META-INF/spring.factories`에 등록한다.
   - `MatchCandidateStoreTest`(단위, 가짜 loader)
     - 비어 있을 때 시작한 생성을 래치로 멈추고 교체를 일으킨다. 생성이 늦게 끝나도 마지막 값이 교체 결과다.
     - 비어 있을 때 동시에 부르면 loader를 한 번만 부른다.
     - (구현 중 변경) 교체에 실패하면 비우고, 다음 요청이 다시 만드는지도 본다.
   - `MatchListApiTest`
     - 수집이 끝나면 바뀐 지원조건이 목록 판정에 반영된다.
     - 부분 실패로 끝난 수집 뒤에도 바뀐 목록이 반영된다.
   - 기존 조건 비교 테스트는 고치지 않고 통과해야 한다.
   - (구현 중 변경) `ApiDocsTest`의 두 목록 API 테스트에서 설명에 "마지막으로 끝난 수집 기준"이 있는지 본다.
   - (구현 중 확인) 수집 이벤트 발행을 빼고 교체에서 잠금을 빼면, 새 테스트 3개(교체 반영 2개, 늦은 생성 1개)가 실패하는 것을 확인했다.
8. **로컬 확인(설계 5의 (다))**: 스냅샷으로 단계 2와 같은 측정을 한다.
   - (구현 중 변경) 요청이 가벼워져 초당 32건 구간을 더했다.
   - (구현 중 확인) 프로필 20개와 빈 프로필을 기본·전체 범위로 받은 응답 42개(쪽 1, 크기 100)를 (가)·(나)·(다)에서 비교했다. 바이트 단위로 같았다. (가)는 커밋 `95c1cdf`를 `git archive`로 따로 풀어 빌드했다.
9. **문서**
   - `docs/decisions/0038-match-list-snapshot.md`(설계 7)를 쓰고, `docs/decisions/README.md` 목록에 더한다.
   - `docs/product/development.md`
     - 결정한 사항 표에 `DEC-FRESHNESS(조건 비교 목록)` 행을 더한다(설계 2).
     - 아직 결정할 사항의 `DEC-FRESHNESS` 행에 조건 비교 목록은 정했다고 적는다. 장애 시 응답 기준과 알림 재검증은 그대로 남긴다.
     - 결정한 사항 앞 문단에 변경 기록 한 줄을 더한다.
   - `scripts/loadtest/run.sh`: `RESULTS_DIR`(설계 6). 머리 주석의 환경 변수 목록에도 더한다. `docs/ops/load-test.md`의 개선 전후 비교 문단에 한 줄을 더한다.
10. **보고서** `docs/research/guest-match-throughput.md`: 측정 기록 형식의 1\~6을 쓴다.
    - 원인 근거: #96 지표, 로컬 크기·분해·실행 계획.
    - 비교한 대안: A·C·D와 고르지 않은 이유, 대안 A의 로컬 측정치.
    - 개선 후 결과: 로컬과 운영. 운영은 #96 표와 같은 열로 쓰고, 혼합은 경로별 p95를 #96과 같은 단계에서 비교한다.
    - 정합성과 한계: 허용 지연, 상세 비교·검색과 어긋나는 구간, 앱 한 대 전제, 데이터 규모의 차이.
11. **[사용자] 운영 측정**(설계 5)
    - 이미지: 브랜치를 push하고 `gh workflow run image.yml --ref improve/98-guest-match-throughput`으로 `sha-<7자리>`를 만든다.
    - `docs/ops/load-test.md`의 순서를 따르되, API를 막은 직후 app VM에서 `.env`의 `APP_TAG`를 `sha-<7자리>`로 바꾸고 `docker compose pull app && docker compose up -d app`을 실행한다. 상태가 `UP`인지 확인한다. 측정 직전 호스트 CPU governor가 #96과 같은지도 확인한다.
    - 부하 VM에서 실행한다.
      - `RESULTS_DIR=docs/research/guest-match-throughput/results INPUTS=<#96 guest-match의 inputs.json> scripts/loadtest/run.sh guest-match`
      - 테스트 계정을 넣은 뒤 `RESULTS_DIR=… INPUTS=<#96 mixed의 inputs.json> scripts/loadtest/run.sh mixed`
    - 끝나면 `.env`의 `APP_TAG`를 `v0.1.1`로 되돌려 `docker compose up -d app`을 실행하고, 상태를 확인한다. 테스트 계정을 지우고 API를 푼다.
12. **보고서 마무리와 PR**: 운영 결과를 보고서에 반영하고 검증 명령을 통과시킨다. 마지막 커밋에서 `status: done`으로 바꾸고 PR을 올린다. PR 본문은 `Closes #98`이다.

## 제안 (이 이슈에서 하지 않음)

- 측정에서 판정이 병목으로 보이면 검토할 것
  - `PersonalTarget`의 구분자 정규식을 미리 컴파일한다(요청 1건 약 1ms).
  - 쪽에 들어갈 항목만 DTO로 만든다(설계 4).

## 완료 조건 매핑

| 이슈 완료 조건 | 충족 방법 |
| --- | --- |
| #96의 조건 비교 시나리오와 같은 조건으로 다시 잰 한계가 기준(초당 7\~8건)보다 높다. | 설계 5, 단계 11. 같은 스크립트·판정 기준·VM에 `INPUTS`로 같은 입력을 준다. 자동 단계가 기준 단계(1·2·4·8)를 포함한다 |
| 프로필 값이 같으면 개선 전과 같은 목록(건수, 판정, 순서)을 돌려준다. 로그인 사용자의 조건 비교 목록도 결과가 같다. | 설계 1(같은 쿼리·파싱·판정을 실행 시점만 옮김), 단계 7. 기존 `MatchListApiTest`·`PublicMatchApiTest`·`MatchApiTest`를 고치지 않고 통과한다. 실데이터 대조는 하지 않는다(사용자 결정) |
| 원인 근거와 측정 결과가 측정 기록 형식에 맞춰 남아 있다. | 단계 2·8·10·12. 보고서 1\~6절, 로컬·운영 원본 결과 |

## 검증 명령

```bash
cd backend && ./gradlew test
bash -n scripts/loadtest/run.sh scripts/loadtest/local-profile.sh
python3 -I -c "import ast; ast.parse(open('scripts/loadtest/local-breakdown.py').read())"
```

로컬 측정(단계 2·8)과 운영 측정(단계 11)은 위 절차대로 하고 결과를 보고서에 남긴다.

## 결정 필요

없음. 2026-11-01 사용자 결정: 방안 B(스냅샷). 허용 지연은 직전 수집 기준. 원인 분해와 대안 A는 로컬에서 재고, 운영 측정은 머지 전 마지막에 한 번 한다. 혼합도 다시 재고, 실데이터 대조는 하지 않는다.
