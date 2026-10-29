status: done

# 구현 계획 — #1 백엔드 Spring Boot 프로젝트 초기 구성

브랜치: `feat/1-backend-init` (develop `32beb35` 기준)

## 목표 및 범위

- 목표: 첫 기능 개발 전에 백엔드 프로젝트, DB 연결, 스키마 관리, 로컬 실행 환경, 테스트 환경을 운영과 같은 MySQL 기준으로 갖춘다.
- 포함: `backend/` Spring Boot 프로젝트, DB 설정(`application.yaml`, `application-local.yaml`), 로컬 MySQL compose, `.env.example`, 루트 `.gitignore`와 `.editorconfig`, Testcontainers 테스트 설정.
- 제외: 엔티티와 첫 Flyway 마이그레이션, API, 운영 배포 구성, 프론트엔드.

## 확인된 사실과 제약

- 작업 내용은 저장소 준비 과정에서 로컬 `wip/bootstrap` 브랜치에 커밋 4개로 먼저 만들어 두었다(`499b0bc`, `dc71785`, `cf5684f`, `9ecf8a5`). 이 브랜치로 옮긴다.
- Spring Boot 4.1.1, Java 21, Gradle 9.7.1 wrapper. Testcontainers 2.x에서 MySQL 컨테이너 클래스는 `org.testcontainers.mysql.MySQLContainer`다.
- MySQL 8.4 기본 정렬 규칙은 `utf8mb4_0900_ai_ci`다. `utf8mb4_unicode_ci`는 이모지를 모두 같은 값으로 비교하고 끝 공백을 무시한다(로컬 MySQL 8.4에서 확인).
- JDBC의 `serverTimezone`은 서버 세션 시간대를 바꾸지 않는다. 시간대를 지정하지 않은 MySQL 컨테이너는 UTC로 뜬다(확인).
- 이미 만들어진 DB의 기본 정렬 규칙은 서버 옵션을 바꿔도 바뀌지 않는다. 로컬 볼륨을 새로 만들어야 적용된다.
- 이 개발 머신은 3306 포트를 다른 컨테이너가 쓰고 있어, 검증은 `MYSQL_PORT=3307`로 한다.

## 설계 결정

1. **테스트 DB는 Testcontainers MySQL, Docker는 필수로 둔다.** H2는 MySQL과 SQL 문법·정렬 규칙이 달라 제외했다. Docker가 없을 때 skip하면 DB 검증이 신호 없이 빠지므로 실패로 둔다.
2. **정렬 규칙은 `utf8mb4_0900_ai_ci`로 로컬 compose와 테스트 컨테이너에 명시한다.** 기본값과 같지만 환경 간 일치를 드러내기 위해 적는다. → [결정 0001](../decisions/0001-db-collation.md)
3. **로컬 MySQL과 테스트 컨테이너 모두 `TZ=Asia/Seoul`로 띄운다.** JDBC `serverTimezone=Asia/Seoul`의 가정과 서버 시간대를 맞춘다. → [결정 0002](../decisions/0002-db-timezone.md)
4. **`.env`는 `spring.config.import=optional:file:.env[.properties]`로 읽는다.** `spring-dotenv`는 Boot 4 호환이 확인되지 않았고 의존성 없이 같은 목적을 이룬다.
5. **`local` 프로필에 로컬 compose 계정 기본값을 둔다.** `.env` 없이 `docker compose up`과 `bootRun`만으로 실행되게 한다. 저장소 지침의 로컬 전용 계정 예외에 해당한다.

## 단계별 구현 계획

1. `wip/bootstrap`의 커밋 4개를 cherry-pick한다.
   - `499b0bc`: Initializr 프로젝트, 루트 `.gitignore`, `TestcontainersConfiguration`, `GovBenefitHelperApplicationTests`.
   - `dc71785`: `application.yaml`, `application-local.yaml`, `backend/.env.example`, 루트 `.env.example`, `docker-compose.local.yml`, `.editorconfig`, Java 파일 스페이스 변환.
   - `cf5684f`: 정렬 규칙을 `utf8mb4_0900_ai_ci`로 변경.
   - `9ecf8a5`: 테스트 컨테이너 시간대를 KST로 변경.
2. `backend/.env.example`의 local 프로필 안내를 "비워 두면"에서 "주석으로 두면"으로 고친다.
3. 루트 `.env.example`의 `MYSQL_BIND` 안내에 외부 접속 허용 시 `MYSQL_ROOT_PASSWORD`, `DB_PASSWORD`를 바꾸라는 경고를 추가한다.

## 완료 조건 매핑

- [ ] 로컬 MySQL을 띄운 뒤 `local` 프로필로 `.env` 없이 앱이 기동한다 → 1단계(`dc71785`)
- [ ] `backend/.env`에 적은 DB 접속 값이 기본값보다 우선 적용된다 → 1단계(`dc71785`)
- [ ] 테스트가 실제 MySQL에서 실행되고, Docker가 없으면 건너뛰지 않고 실패한다 → 1단계(`499b0bc`)
- [ ] 테스트 DB와 로컬 DB의 문자 정렬 규칙과 시간대가 같다 → 1단계(`cf5684f`, `9ecf8a5`)
- [ ] `.env`는 커밋 대상에서 제외되고 `.env.example`은 커밋된다 → 1단계(`499b0bc`)
- [ ] `.env.example` 안내를 그대로 따라 해도 빈 값이 로컬 기본 계정을 덮어쓰지 않는다 → 2단계

## 검증 명령

- `backend/`에서 `./gradlew test`
- `MYSQL_PORT=3307 docker compose -f docker-compose.local.yml up -d --wait` 후 DB의 정렬 규칙과 시간대 조회
- `backend/.env`에 `DB_PORT=3307`만 두고 `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun --args='--server.port=8090'`로 기동 확인
- `git check-ignore -v --no-index .env backend/.env .env.example backend/.env.example`

## 결정 필요

없음
