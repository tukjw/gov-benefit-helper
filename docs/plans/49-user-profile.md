status: in-progress

# 구현 계획 — #49 로그인한 사용자의 최소 프로필 저장·조회·수정 제공

브랜치: `feat/49-user-profile` (develop `bb4d2c9` 기준)

## 목표 및 범위

- 목표: 로그인한 사용자가 `DEC-PROFILE`의 최소 프로필(생년월일, 거주 시도·시군구, 성별, 가구·생애 속성)을 저장·조회·수정한다. 모두 선택 입력이고, 미입력과 '해당 없음'을 구분하며, 지원하지 않는 값과 모순된 입력은 항목별 오류로 거절한다.
- 포함:
  - `GET /api/me/profile`, `PUT /api/me/profile`.
  - 선택지 API `GET /api/me/profile/options`: 시도별 시군구 목록과 두 속성 그룹의 값·이름(설계 9).
  - 시도·시군구 기준 테이블 `region`과 시드(행정표준기관코드), 프로필 테이블 `user_profile`·`user_profile_attribute`와 Flyway 마이그레이션.
  - 생애·가구 속성 enum과 지원조건 코드(`JA03xx`·`JA04xx`) 매핑.
  - 생년월일로 만 나이 계산(응답에 포함).
  - 항목 오류를 여러 개 담는 `InvalidInputException` 생성자.
  - 결정 문서 0021(거주 지역 코드)·0022(프로필 속성 값과 미입력 표현). API 명세와 `ApiDocsTest`.
- 제외:
  - 조건 비교에 프로필 적용: 조건 비교 이슈(`REQ-MATCH-01`).
  - 운영 기관 지역 필터의 기본값: `REQ-SEARCH-01` P1.
  - 탈퇴 시 프로필 삭제 절차: 탈퇴 이슈. 이 이슈는 FK `ON DELETE CASCADE`만 둔다(기존 `password_reset_token`과 같은 방식).
  - 행정구역 개편 시 기존 프로필의 지역 코드 처리: 개편이 생길 때 별도 이슈(설계 2).

## 확인된 사실과 제약

- 공공서비스 API 목록의 `소관기관코드`는 행정표준기관코드(7자리)다. 수집은 이 값을 가공하지 않고 `benefit.agency_code`에 넣는다(`Gov24RecordMapper.java:57`). 원문(`source_record`)과 `agency_code`를 전체 비교해 다른 값이 0건이었다(2026-10-17, 로컬 DB 2026-10-01 수집분). 같은 날 실제 API를 호출해 종로구시설관리공단 `3000000`, 전남광주통합특별시 동구 `5805000`, 양산시인재육성장학재단 `5380000`을 확인했다.
- 2026-10-01 수집분의 소관기관유형 `광역시도`는 16개 코드·16개 이름, `시군구`는 227개 코드·227개 이름이다. 코드가 없는 행은 0건이다.
  - 2026년 개편이 반영돼 있다. 광주광역시와 전라남도는 `전남광주통합특별시`(`6130000`) 하나이고 시군구 27개를 가진다. 인천은 제물포구·영종구·서해구·검단구를 포함해 11개다.
  - 시군구 이름은 모두 `<시도명> <시군구명>` 형식이고, 앞의 시도명은 모두 광역시도 16개 중 하나와 같다. 같은 이름에 코드가 둘 이상인 경우는 없다.
  - 일반구는 없다. 수원·창원 혜택은 `경기도 수원시`·`경상남도 창원시`로만 나온다.
  - 세종(`5690000`)은 `광역시도`로만 나오고, 제주(`6500000`)는 시군구 혜택이 0건이다(제주시·서귀포시는 행정시).
  - 시군구 코드는 시도 코드를 앞자리로 포함하지 않는다. 동구 `5805000`이 `6130000` 아래라는 것은 이름으로만 알 수 있다.
  - 지방공기업 561건과 지방출자·출연기관 580건의 `agency_code`는 모두 시도나 시군구 코드와 같다(산하 기관이 상위 자치단체 코드를 쓴다). 중앙행정기관·공공기관·교육청은 겹치지 않는다.
  - 개편으로 기관코드가 바뀐다. 전남광주통합특별시 동구 혜택의 서비스ID는 `359…`(통합 전 광주 동구 코드로 보임)로 시작하지만 소관기관코드는 `5805000`이다.
- 지원조건 코드표([보고서 §5](../research/gov24-api-data.md#5-지원조건-코드)): 생애·직업·건강 17개(`JA0301`~`JA0330`, 해당사항없음 `JA0322`), 가구 9개(`JA0401`~`JA0414`, 해당사항없음 `JA0410`). 해당사항없음은 그룹마다 하나다.
- 결정 0018: 개인 API는 사용자 ID를 경로·파라미터·본문으로 받지 않고 `@CurrentUserId Long userId`만 쓴다. `/api/me/**`는 공개 경로가 아니라 보안 설정을 바꾸지 않는다. 토큰 주인의 계정이 없으면 `AccountService.me`처럼 401 `UNAUTHENTICATED`다.
- 결정 0009: 항목 오류는 `errors[].field·value·reason`. `InvalidInputException`은 지금 오류 하나만 담는다. Bean Validation 실패(`handleMethodArgumentNotValid`)는 여러 개를 담는다.
- `GlobalExceptionHandler`는 본문 읽기 실패(`HttpMessageNotReadableException`)를 따로 처리하지 않아, 상위 클래스가 400을 정하고 `errors`가 빈 `INVALID_INPUT`이 나간다. 요청 DTO의 enum·`LocalDate` 필드에 지원하지 않는 값이 오면 어느 항목인지 응답에 담기지 않는다.
- `Clock` 빈은 `CollectionConfig`에 있다(`Asia/Seoul`, 결정 0002). 테스트는 `kr.govbenefit.MutableClock`으로 시각을 옮긴다.
- 테스트 `setUp`은 `DELETE FROM user_account`로 정리한다. `ON DELETE CASCADE`인 테이블은 함께 지워진다.
- Jackson은 3(`tools.jackson`)이다. 요청 본문의 알 수 없는 속성을 무시하는지는 구현 중 테스트로 확인한다(설계 6).
  - 구현 중 확인: 무시한다. `userId`·`income`·`residentRegistrationNumber`를 넣은 저장 요청이 200이고 저장되지 않는다(`ProfileApiTest`).
- 결정 0016: 컨트롤러는 `ResponseEntity<응답 DTO>`를 반환한다. 결정 0010: 응답 시각은 `ApiTimes.toOffset`.

## 설계 결정

1. **거주 지역은 행정표준기관코드로 식별한다.** 결정 문서 0021. 2026-10-17 사용자 결정.
   - 프로필 값과 혜택의 `agency_code`가 같은 체계라 운영 기관 지역 필터(P1)가 매핑 없이 코드 비교로 끝난다. 산하 지방공기업·출연기관도 같은 코드로 지역이 정해진다.
   - 대안: 법정동코드(5자리). 주소 체계 표준이지만 기관코드와의 매핑이 따로 필요하고, 개편이 반영된 원본 파일을 따로 받아야 한다. 주소 기반 기능을 붙일 때 다시 검토한다.
   - 대안: 정적 enum·JSON. 개편 때 재배포가 필요하고 DB가 값을 검증하지 못한다.
   - 대안: 수집 결과에서 매번 목록을 만듦. 수집에 따라 목록이 흔들려 입력 검증 기준이 될 수 없다. 이 이슈는 2026-10-01 수집분에서 한 번 뽑아 마이그레이션으로 고정한다.
2. **지역 범위.** 결정 문서 0021. 2026-10-17 사용자 결정.
   - 시도 16개와 자치 시군구 227개만 둔다. 일반구는 받지 않는다.
   - 세종·제주는 시군구가 없다. 시군구 없이 시도만 입력할 수 있고, 시도 없이 시군구만 보내면 거절한다.
   - 개편으로 폐지된 코드를 가진 프로필의 처리는 이 이슈에서 다루지 않는다. 폐지 표시 컬럼도 두지 않는다. 개편이 생기면 새 코드 시드와 기존 프로필 처리를 한 마이그레이션 이슈로 다룬다. 0021 영향에 적는다.
3. **생애·가구 속성은 그룹별 필드와 명시적 `NONE`으로 표현한다.** 결정 문서 0022. 2026-10-17 사용자 결정.
   - 요청·응답 필드는 `lifeAttributes`(생애·직업·건강)와 `householdAttributes`(가구)다. 각 필드는 `null`이면 미입력, `["NONE"]`이면 해당 없음, 그 밖에는 고른 속성 목록이다.
   - 빈 배열은 거절한다. 미입력이면 `null`, 해당 없음이면 `["NONE"]`을 보낸다. 클라이언트의 빈 배열이 의도와 다르게 저장되지 않게 한다.
   - 같은 그룹에서 `NONE`과 다른 값을 함께 보내거나, 같은 값을 두 번 보내거나, 다른 그룹의 값이나 `null` 원소를 보내면 거절한다.
   - API 값은 자체 enum 이름이고 각 값이 JA 코드를 가진다. API를 읽기 쉽게 하고 원천 코드 체계와 분리한다. 표는 단계 3에 있다.
   - 대안: `null` 미입력·`[]` 해당 없음. 이슈의 "해당 없음과 다른 속성을 함께 고른 입력"을 표현할 수 없다.
   - 대안: 그룹마다 `{status, values}`. 응답이 장황하고 상태와 값의 모순 검증이 늘어난다.
   - 대안: JA 코드를 API 값으로 노출. 매핑은 없지만 API가 원천 코드에 묶이고 읽기 어렵다.
4. **저장 모델.** 결정 문서 0022(속성 저장).
   - `user_profile`: 사용자당 한 행(PK `user_id`, `user_account`를 `ON DELETE CASCADE`로 참조). 생년월일·성별·시도·시군구는 nullable 컬럼이다. 계정 테이블과 분리해 인증 정보와 개인정보를 나눈다.
   - `user_profile_attribute`: 고른 속성 한 개에 한 행. 값은 **JA 코드**로 저장한다(`NONE`도 `JA0322`·`JA0410` 행). 그룹에 행이 없으면 미입력이다. JA 코드는 그룹 사이에 겹치지 않아 그룹 컬럼이 필요 없다. 조건 비교가 혜택의 원문 코드와 바로 비교할 수 있고, enum 이름을 바꿔도 데이터를 옮기지 않는다. `CHECK` 제약으로 26개 코드만 허용한다.
   - 대안: JSON 컬럼. 한 행이라 단순하지만 값 무결성을 DB가 막지 못하고, 이후 알림 대상 선정에서 "속성 X를 가진 사용자"를 찾으려면 MySQL 전용 함수나 멀티밸류 인덱스가 필요하다.
   - 대안: `SET`·비트마스크. 값 추가 때 ALTER와 순서 고정이 필요하다.
   - 시군구가 시도에 속하는지는 서비스에서 검증한다(설계 6). FK는 각 컬럼이 `region`에 있는지만 본다.
5. **API는 `PUT` 전체 교체와 `GET`이다.** 2026-10-17 사용자 결정.
   - `PUT /api/me/profile`: 본문 전체가 새 프로필이다. 빠진 필드와 `null`은 미입력이다. 처음이면 만들고 있으면 바꾼다. 200과 저장된 프로필을 돌려준다.
   - `GET /api/me/profile`: 저장한 적이 없으면 200에 모든 값 `null`(`updatedAt`도 `null`)이다. 조건 비교에서 미입력은 프로필이 없는 것과 같게 다루므로(`REQ-MATCH-01`) 404로 나누지 않는다. 조회는 행을 만들지 않는다.
   - 응답은 지역 코드와 이름을 함께 준다. 화면이 코드만으로 이름을 그릴 수 없기 때문이다.
   - 대안: `PATCH`. "보내지 않음"과 "미입력으로 되돌림(`null`)"을 구분할 장치가 필요해 이 이슈의 핵심인 미입력 표현과 충돌한다.
   - 대안: `POST` 생성 + `PUT` 수정. 409·404 분기만 늘어난다.
6. **입력 검증은 요청을 문자열로 받아 `ProfileValidator` 한곳에서 한다.** 2026-10-17 사용자 결정(오류 여러 개, 120세 상한).
   - DTO 필드는 `String`과 `List<String>`이다. enum·`LocalDate`로 받으면 지원하지 않는 값이 본문 읽기 실패가 되어 어느 항목인지 응답에 담기지 않는다.
   - 모든 항목을 검사해 오류를 모은 뒤 `InvalidInputException(List<InputError>)`으로 한 번에 던진다. 폼이 오류를 한 번에 보여줄 수 있고, Bean Validation 오류가 여러 개 나가는 것과 맞춘다.
   - 규칙(필드, 거절 이유):
     - `birthDate`: `yyyy-MM-dd`이고 실제 날짜(`ResolverStyle.STRICT`, `2001-02-29` 거절). 오늘(`Asia/Seoul`)보다 뒤면 거절. 오늘 기준 만 나이가 120을 넘으면 거절(120세는 허용). 상한은 지원조건 연령 코드의 상한 120과 맞춘다.
     - `gender`: `MALE`·`FEMALE`. 지원조건 성별 코드가 둘뿐이라 다른 값을 두지 않는다.
     - `sidoCode`: `region`에 있는 시도.
     - `sigunguCode`: `sidoCode`가 없으면 거절. `region`에 있는 시군구이고 상위가 `sidoCode`여야 한다. 세종·제주는 시군구가 없으므로 어떤 값이든 거절된다.
     - `lifeAttributes`·`householdAttributes`: 설계 3.
   - 오류의 `value`: 단일 값은 받은 값, 속성 목록은 문제가 된 원소(지원하지 않는 값·중복·`null`) 또는 `NONE`과 함께 고른 경우 목록 전체를 쉼표로 이은 값이다.
   - 본문의 알 수 없는 속성(`userId`, `income`, `residentRegistrationNumber` 등)은 DTO에 없으므로 저장되지 않는다. 기본 설정이 무시하는지 거절하는지는 구현 중 확인하고, 무시하면 그대로 둔다. 거절하면 테스트의 기대 상태 코드만 400으로 바꾼다. 어느 쪽이든 저장하지 않는다는 완료 조건은 같다. 구현 중 확인: 무시한다(200).
   - 대안: Bean Validation 커스텀 제약. `@Past`는 시스템 시계를 써서 `MutableClock`으로 경계를 고정하려면 `ClockProvider` 설정이 필요하고, 교차 검증(시군구-시도, `NONE` 조합)은 어차피 서비스에 남는다.
   - 대안: 본문 읽기 실패에서 필드 경로를 뽑도록 `GlobalExceptionHandler`를 고침. 모든 API에 영향이 있어 이 이슈 범위를 넘는다.
7. **만 나이는 조회할 때 계산하고 저장하지 않는다.** 2026-10-17 사용자 결정.
   - `Ages.fullAge(LocalDate birthDate, LocalDate today)` = `Period.between(birthDate, today).getYears()`. 오늘은 `LocalDate.now(clock)`.
   - 2월 29일생은 평년에 3월 1일부터 나이가 오른다(`Period.between`의 동작). 민법의 기간 계산(말일이 없으면 그 달 말일 만료)과 같은 결과다.
8. **같은 사용자의 동시 저장은 계정 행을 잠가 직렬화한다.**
   - `PUT`은 `UserAccountRepository.findByIdForUpdate`(`PESSIMISTIC_WRITE`)로 계정을 잠근 뒤 프로필을 찾거나 만든다. 처음 저장하는 요청 둘이 동시에 와도 PK 충돌 500 없이 나중 요청이 덮어쓴다.
   - 계정이 없으면(토큰은 유효하지만 계정이 지워짐) 401 `UNAUTHENTICATED`다(`AccountService.me`와 같음).
   - 대안: unique 위반을 잡아 다시 시도. 재시도 경로가 생기고 속성 행의 교체와 섞인다.
9. **선택지는 `GET /api/me/profile/options` 하나로 준다.** 2026-10-17 사용자 결정.
   - 지역은 7자리 코드라 클라이언트가 목록 없이는 값을 만들 수 없고, 속성은 API 명세의 `allowableValues`에 한국어 이름이 없다.
   - 응답: `sidos`(시도 순서대로 `code`·`name`·`sigungus[]`, 시군구는 `code`·`name`. 세종·제주는 빈 배열), `lifeAttributes`·`householdAttributes`(enum 선언 순서로 `value`·`label`, `NONE`이 마지막).
   - 프로필 화면은 로그인한 뒤에만 쓰므로 인증 경로에 둔다. 공개 경로 목록(결정 0018)은 바꾸지 않는다.
   - 값은 배포 사이에 바뀌지 않지만 캐시 헤더는 두지 않는다. 성능은 측정한 뒤 다룬다(`docs/product/development.md`).
   - 대안: 별도 이슈로 미룸. 그때까지 Swagger에서도 코드를 따로 찾아 넣어야 한다.
   - 대안: 지역(`/api/regions`)과 속성을 나눈 공개 API. 지역 필터(P1)도 쓸 수 있지만 공개 경로를 늘리려면 결정 0018을 대체해야 한다. 지역 필터 이슈에서 필요하면 그때 나눈다.

## 단계별 구현 계획

1. **마이그레이션**
   - `V8__create_region.sql`: 테이블과 시드 243행.
     ```sql
     -- 거주 지역 기준(#49, 결정 0021). 코드는 행정표준기관코드로 공공서비스 API의 소관기관코드와 같다.
     -- 시도 16개와 자치 시군구 227개이며 2026-10-01 수집분에서 뽑았다. 세종·제주는 시군구가 없다.
     CREATE TABLE region (
         code          CHAR(7)     NOT NULL,
         name          VARCHAR(30) NOT NULL,
         parent_code   CHAR(7)     NULL,
         display_order INT         NOT NULL,
         PRIMARY KEY (code),
         CONSTRAINT fk_region_parent FOREIGN KEY (parent_code) REFERENCES region (code),
         INDEX idx_region_parent_order (parent_code, display_order)
     );
     INSERT INTO region (code, name, parent_code, display_order) VALUES
         ('6110000', '서울특별시', NULL, 1),
         ...
         ('3000000', '종로구', '6110000', 1),
         ...;
     ```
     - `name`은 시도는 기관명 그대로, 시군구는 기관명에서 시도명을 뗀 값(`강남구`)이다.
     - 시도 순서: 서울, 부산, 대구, 인천, 대전, 울산, 세종, 경기, 강원, 충북, 충남, 전북, 전남광주, 경북, 경남, 제주. 시군구 순서는 같은 시도 안에서 코드 오름차순이다.
     - 시드는 로컬 DB에서 다음으로 뽑아 SQL에 붙인다. 뽑은 뒤 16·227행인지 확인한다.
       ```sql
       SELECT DISTINCT agency_code, agency_name FROM benefit WHERE agency_type = '광역시도';
       SELECT DISTINCT agency_code, agency_name FROM benefit WHERE agency_type = '시군구';
       ```
   - `V9__create_user_profile.sql`
     ```sql
     -- 최소 프로필(#49, DEC-PROFILE). 사용자당 한 행이며 모든 값은 선택 입력이라 NULL이 미입력이다.
     CREATE TABLE user_profile (
         user_id      BIGINT      NOT NULL,
         birth_date   DATE        NULL,
         gender       VARCHAR(10) NULL,
         sido_code    CHAR(7)     NULL,
         sigungu_code CHAR(7)     NULL,
         created_at   DATETIME(6) NOT NULL,
         updated_at   DATETIME(6) NOT NULL,
         PRIMARY KEY (user_id),
         CONSTRAINT fk_user_profile_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
         CONSTRAINT fk_user_profile_sido FOREIGN KEY (sido_code) REFERENCES region (code),
         CONSTRAINT fk_user_profile_sigungu FOREIGN KEY (sigungu_code) REFERENCES region (code),
         CONSTRAINT ck_user_profile_gender CHECK (gender IN ('MALE', 'FEMALE'))
     );
     -- 생애·가구 속성(결정 0022). 지원조건 코드로 저장하며 해당사항없음(JA0322, JA0410)도 한 행이다. 그룹에 행이 없으면 미입력이다.
     CREATE TABLE user_profile_attribute (
         user_id        BIGINT  NOT NULL,
         condition_code CHAR(6) NOT NULL,
         PRIMARY KEY (user_id, condition_code),
         CONSTRAINT fk_user_profile_attribute_profile FOREIGN KEY (user_id) REFERENCES user_profile (user_id) ON DELETE CASCADE,
         CONSTRAINT ck_user_profile_attribute_code CHECK (condition_code IN (<26개 코드>))
     );
     ```
2. **지역 도메인** `kr.govbenefit.region`
   - `domain.Region`(엔티티): `code`, `name`, `parentCode`, `displayOrder`. `isSido()`.
   - `repository.RegionRepository`: `Optional<Region> findById(String code)`, `List<Region> findAllByOrderByDisplayOrderAsc()`(선택지, 설계 9). 조회는 지역 필터 이슈도 쓰므로 프로필 패키지가 아니라 여기에 둔다.
3. **프로필 도메인** `kr.govbenefit.profile.domain`
   - `Gender`: `MALE`, `FEMALE`.
   - `LifeAttribute`(`jaCode`, `label`)

     | 값 | 코드 | 이름 |
     | --- | --- | --- |
     | `PROSPECTIVE_PARENT` | `JA0301` | 예비부모/난임 |
     | `PREGNANT` | `JA0302` | 임산부 |
     | `CHILDBIRTH_ADOPTION` | `JA0303` | 출산/입양 |
     | `FARMER` | `JA0313` | 농업인 |
     | `FISHER` | `JA0314` | 어업인 |
     | `LIVESTOCK_FARMER` | `JA0315` | 축산업인 |
     | `FORESTER` | `JA0316` | 임업인 |
     | `ELEMENTARY_STUDENT` | `JA0317` | 초등학생 |
     | `MIDDLE_SCHOOL_STUDENT` | `JA0318` | 중학생 |
     | `HIGH_SCHOOL_STUDENT` | `JA0319` | 고등학생 |
     | `UNIVERSITY_STUDENT` | `JA0320` | 대학생/대학원생 |
     | `EMPLOYEE` | `JA0326` | 근로자/직장인 |
     | `JOB_SEEKER` | `JA0327` | 구직자/실업자 |
     | `DISABLED` | `JA0328` | 장애인 |
     | `VETERAN` | `JA0329` | 국가보훈대상자 |
     | `PATIENT` | `JA0330` | 질병/질환자 |
     | `NONE` | `JA0322` | 해당사항없음 |

   - `HouseholdAttribute`(`jaCode`, `label`)

     | 값 | 코드 | 이름 |
     | --- | --- | --- |
     | `MULTICULTURAL_FAMILY` | `JA0401` | 다문화가족 |
     | `NORTH_KOREAN_DEFECTOR` | `JA0402` | 북한이탈주민 |
     | `SINGLE_PARENT_OR_GRANDPARENT_FAMILY` | `JA0403` | 한부모가정/조손가정 |
     | `SINGLE_PERSON_HOUSEHOLD` | `JA0404` | 1인가구 |
     | `MULTI_CHILD_HOUSEHOLD` | `JA0411` | 다자녀가구 |
     | `NON_HOMEOWNER_HOUSEHOLD` | `JA0412` | 무주택세대 |
     | `NEW_RESIDENT` | `JA0413` | 신규전입 |
     | `EXTENDED_FAMILY` | `JA0414` | 확대가족 |
     | `NONE` | `JA0410` | 해당사항없음 |

     두 enum에 `static Optional<…> fromJaCode(String)`을 둔다.
     - 구현 중 변경: 두지 않는다. `UserProfile`이 enum 값을 선언 순서로 돌며 코드가 저장돼 있는지 보면 되므로 코드로 값을 찾을 일이 없다.
   - 구현 중 추가: 두 enum의 공통 모양(`jaCode`, `label`)을 인터페이스 `ProfileAttribute`로 둔다. 선택지 응답이 두 그룹을 같은 코드로 만든다.
   - 구현 중 변경: `ProfileValidator`는 속성을 문자열 단계에서 검사하고(허용 값 목록, 중복, `NONE` 조합), 통과한 뒤에만 enum으로 바꾼다. 제네릭 공통 메서드와 enum 변환을 한 메서드에서 하던 방식이 과해 바꿨다. `null` 원소는 지원하지 않는 값과 같은 문구로 거절한다.
   - `Ages.fullAge(LocalDate birthDate, LocalDate today)`(설계 7).
   - `UserProfile`(엔티티): 컬럼은 V9와 같고 속성은 `@ElementCollection` `Set<String> conditionCodes`(`@CollectionTable(name = "user_profile_attribute")`). `static create(Long userId, LocalDateTime now)`, `replace(ProfileValues values, LocalDateTime now)`, `Set<LifeAttribute> lifeAttributes()`·`Set<HouseholdAttribute> householdAttributes()`(그룹에 행이 없으면 `null`).
     - 구현 중 변경: 두 메서드는 제네릭 공통 메서드 없이 각각 선언 순서의 `List`를 돌려준다. 그룹이 둘뿐이라 세 줄 중복이 추상화보다 읽기 쉽다. 응답이 선언 순서라 서비스에서 다시 정렬하지 않는다.
   - `ProfileValues`(record): 검증을 마친 값. `birthDate`, `gender`, `sidoCode`, `sigunguCode`, `Set<LifeAttribute>`·`Set<HouseholdAttribute>`(미입력은 `null`).
4. **저장소**
   - `profile.repository.UserProfileRepository extends JpaRepository<UserProfile, Long>`.
   - `account.repository.UserAccountRepository.findByIdForUpdate(Long id)`: `@Lock(PESSIMISTIC_WRITE)`(설계 8).
5. **공통 API**
   - `InvalidInputException(List<InputError> errors)` 생성자 추가. 기존 단일 오류 생성자는 그대로 둔다. 빈 목록은 `IllegalArgumentException`.
6. **서비스** `kr.govbenefit.profile.service`
   - `ProfileValidator`(`@Component`, `RegionRepository`·`Clock`): `ProfileValues validate(ProfileRequest request)`. 설계 6의 규칙을 모두 검사하고 오류가 있으면 한 번에 던진다.
   - `ProfileService`
     - `ProfileResponse get(Long userId)`: 계정이 없으면 401. 프로필이 없으면 빈 응답.
     - `@Transactional ProfileResponse put(Long userId, ProfileRequest request)`: 검증 → 계정 잠금(설계 8) → 찾거나 만들기 → `replace` → 응답.
     - 응답의 지역 이름은 `RegionRepository`로 채운다. 나이는 `Ages.fullAge(birthDate, LocalDate.now(clock))`.
     - `ProfileOptionsResponse options()`: 지역 전체를 한 번 읽어 시도 아래에 시군구를 묶고, 두 enum을 선택지로 바꾼다(설계 9).
7. **DTO와 컨트롤러** `kr.govbenefit.profile`
   - `dto.ProfileRequest(String birthDate, String gender, String sidoCode, String sigunguCode, List<String> lifeAttributes, List<String> householdAttributes)`. `@Schema`에 허용 값(`allowableValues`)과 미입력·`NONE`·빈 배열 규칙을 적는다.
   - `dto.ProfileResponse(LocalDate birthDate, Integer age, Gender gender, RegionResponse sido, RegionResponse sigungu, List<LifeAttribute> lifeAttributes, List<HouseholdAttribute> householdAttributes, OffsetDateTime updatedAt)`. 속성 목록은 enum 선언 순서로 정렬한다. 사용자 ID는 넣지 않는다(결정 0018).
   - `dto.RegionResponse(String code, String name)`.
   - `dto.ProfileOptionsResponse(List<SidoOption> sidos, List<AttributeOption> lifeAttributes, List<AttributeOption> householdAttributes)`, `SidoOption(String code, String name, List<RegionResponse> sigungus)`, `AttributeOption(String value, String label)`.
   - `controller.ProfileController`(`/api/me/profile`, `@Tag(name = "프로필")`): `GET`, `PUT`, `GET /options`. `@Operation`·`@ApiResponse`에 200, 400 `INVALID_INPUT`(`PUT`만, 항목 오류 예시), 401 `UNAUTHENTICATED`.
8. **API 명세** `OpenApiConfig.DESCRIPTION`에 프로필 항목과 미입력·해당 없음 규칙을 한 문단 더한다.
9. **테스트**
   - 단위
     - `AgesTest`: 2000-05-17생이 2026-05-16에 25, 2026-05-17에 26, 2026-05-18에 26. 2000-02-29생이 2025-02-28에 24, 2025-03-01에 25, 2028-02-29에 28.
     - `ProfileAttributeTest`: JA 코드와 이름이 코드표와 같고 두 그룹 사이에 겹치지 않으며 `NONE`이 `JA0322`·`JA0410`이고 마지막이다.
     - `BusinessExceptionTest`(기존 입력 오류 테스트 옆에 추가): 여러 오류를 순서대로 담고, 빈 목록은 거절한다.
   - `RegionSeedTest`(`@SpringBootTest`, Testcontainers): 시도 16·시군구 227행. 모든 시군구의 상위가 시도. 세종 `5690000`·제주 `6500000`은 하위가 없다. 서울 `6110000`과 강남구 `3220000`(상위 서울), 전남광주통합특별시 `6130000`과 동구 `5805000`(상위 `6130000`).
   - `ProfileApiTest`(`@SpringBootTest`, MockMvc, Testcontainers, `MutableClock`)
     - 구현 중 변경: 계정은 `AccessTokenAuthTest`처럼 저장소로 만들고 토큰은 `AccessTokenIssuer`로 발급한다. 가입 흐름은 이 이슈의 대상이 아니다. 토큰 만료도 같은 시계로 판정하므로 시계를 옮기는 테스트는 요청마다 토큰을 새로 발급한다.
     - 구현 중 추가: 날짜 경계가 한국 시간(23:59:59 / 00:00)인지, 시도가 틀리면 시군구 소속 오류를 따로 내지 않는지, 토큰은 유효하지만 계정이 없으면 401인지.
     - 저장 후 조회하면 같은 값이고, 다시 저장 후 조회하면 바뀐 값이다. 항목을 `null`로 바꾸면 미입력으로 돌아간다.
     - 저장한 적 없는 사용자의 조회는 200에 모든 값 `null`이고 `user_profile` 행이 생기지 않는다.
     - 빈 본문(`{}`)을 저장하면 200이고 조회 결과는 모든 값 `null`, `updatedAt`만 있다.
     - 미입력(`null`)과 해당 없음(`["NONE"]`)이 응답에서 다르게 나온다. 두 그룹 각각.
     - 만 나이: 시계를 생일 전날·당일·다음 날로 옮겨 `age`가 25·26·26.
     - 시도만 입력, 세종만 입력은 저장된다.
     - 거절(400 `INVALID_INPUT`, `errors[].field` 확인, 저장된 값이 바뀌지 않음): `gender: "OTHER"`; `lifeAttributes: ["UNKNOWN"]`; 가구 값을 `lifeAttributes`에 넣음; `sidoCode: "9999999"`; 시군구 코드를 `sidoCode`에 넣음; `birthDate`가 내일, `2001-02-29`, `20000517`; 만 120세 + 1일(만 121세 생일 당일)은 거절, 만 120세는 허용; 서울 시도에 동구(`5805000`); 시도 없이 시군구; 세종에 시군구; 같은 그룹에서 `NONE`과 다른 값; 중복 값; 빈 배열.
     - 오류 여러 개: `gender`와 `sidoCode`가 함께 틀리면 `errors`에 두 항목.
     - 인증: 토큰 없이 `GET`·`PUT`은 401. 본문에 다른 사용자의 ID(`userId`)를 넣고 저장해도 그 사용자의 프로필은 바뀌지 않고 자기 프로필만 바뀐다. A가 저장한 값을 B가 조회하면 B의 빈 프로필이다.
     - 범위 밖 정보: 본문에 `income`, `residentRegistrationNumber`를 넣어도 응답과 DB(`user_profile`·`user_profile_attribute` 컬럼)에 저장되지 않는다(200, 설계 6).
     - 프로필이 없는 로그인 사용자와 비로그인 사용자 모두 `GET /api/benefits`·`GET /api/benefits/{serviceId}`가 200이다.
     - 계정을 지우면 프로필과 속성 행이 함께 지워진다(FK 확인).
     - 선택지: 토큰 없이 401. `sidos`는 16개이고 서울이 처음, 제주가 마지막. 서울의 `sigungus`는 25개이고 종로구(`3000000`)가 처음. 세종·제주의 `sigungus`는 빈 배열. 생애 17개·가구 9개이고 각 그룹의 마지막이 `NONE`(`해당사항없음`). 선택지에서 고른 코드·값으로 `PUT`하면 저장된다.
   - `ProfileConcurrencyTest`(`@SpringBootTest`, Testcontainers): 프로필이 없는 사용자의 `ProfileService.put` 8개를 `CountDownLatch`로 동시에 실행하면 모두 성공하고 `user_profile`은 한 행, 속성은 마지막에 커밋된 요청의 값이다.
   - `ApiDocsTest`: `/api/me/profile`의 `get`·`put`과 `/api/me/profile/options`의 `get`이 bearer 보안을 요구하고, 요청 스키마에 `userId`가 없으며, `gender`·속성의 `allowableValues`, 응답 코드(`put`은 200·400·401·500, 나머지는 200·401·500).
   - 기존 테스트는 바꾸지 않고 통과한다.
10. **문서**
    - `docs/decisions/0021-residence-region-code.md`: 설계 1·2와 선택지 API의 지역 목록(설계 9). 영향: 운영 기관 지역 필터가 `region`과 `agency_code`로 기본값을 정할 것, 개편 시 시드와 기존 프로필을 함께 옮기는 마이그레이션 이슈를 만들 것, 주소 기반 기능을 붙이면 법정동코드 매핑을 다시 검토할 것.
    - `docs/decisions/0022-profile-attributes.md`: 설계 3·4. 영향: 조건 비교가 `user_profile_attribute.condition_code`를 혜택의 지원조건 코드와 비교할 것, 원천에 코드가 추가되면 enum·`CHECK`·마이그레이션을 함께 바꿀 것.
    - `docs/decisions/README.md` 목록 표에 0021·0022.
    - `docs/decisions/0009-api-error-response.md` 적용 위치의 `InvalidInputException` 설명에 "여러 항목 오류도 담는다(#49)"를 더한다.

## 완료 조건 매핑

- [ ] 로그인한 사용자가 프로필을 저장한 뒤 조회하면 저장한 값이, 수정한 뒤 조회하면 바뀐 값이 나온다. → 설계 5. `ProfileApiTest`의 저장·수정.
- [ ] 아무 항목도 입력하지 않은 프로필도 저장·조회할 수 있고, 미입력과 '해당 없음'이 응답에서 구분된다. → 설계 3·5. `ProfileApiTest`의 빈 본문·`null`과 `["NONE"]`.
- [ ] 생년월일로 계산한 만 나이가 생일 전날·당일·다음 날 경계에서 맞다. → 설계 7. `AgesTest`, `ProfileApiTest`의 시계 이동.
- [ ] 지원하지 않는 성별·속성·지역 값, 미래 생년월일, 시도에 속하지 않는 시군구, 같은 그룹에서 '해당 없음'과 다른 속성을 함께 고른 입력은 해당 항목을 가리키는 오류로 거절된다. → 설계 6. `ProfileApiTest`의 거절 사례.
- [ ] 인증되지 않은 요청은 거절되고, 요청에 다른 사용자 ID를 넣어도 그 사용자의 프로필을 읽거나 바꿀 수 없다. → 결정 0018, 설계 6. `ProfileApiTest`의 인증.
- [ ] 주민등록번호·소득·증빙서류 등 위 항목 밖의 정보를 요구하거나 저장하지 않는다. → 설계 4·6. 스키마에 컬럼이 없고, `ProfileApiTest`의 범위 밖 정보, `ApiDocsTest`의 요청 스키마.
- [ ] 프로필이 없는 사용자도 검색·상세 조회를 그대로 사용할 수 있다. → `ProfileApiTest`의 검색·상세.

## 검증 명령

- `backend/`에서 `./gradlew test`
- 로컬 확인: `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` 뒤 가입·로그인으로 액세스 토큰을 받고
  ```sh
  curl -s localhost:8080/api/me/profile -H "Authorization: Bearer $TOKEN"
  curl -s -X PUT localhost:8080/api/me/profile -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"birthDate":"1995-03-01","gender":"FEMALE","sidoCode":"6110000","sigunguCode":"3220000","lifeAttributes":["EMPLOYEE"],"householdAttributes":["NONE"]}'
  curl -s -X PUT localhost:8080/api/me/profile -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"gender":"OTHER","sidoCode":"6110000","sigunguCode":"5805000","lifeAttributes":["NONE","EMPLOYEE"]}'
  ```
  마지막 요청이 `gender`·`sigunguCode`·`lifeAttributes` 세 항목 오류인지 본다. `curl -s localhost:8080/api/me/profile/options -H "Authorization: Bearer $TOKEN"`로 선택지를 본다.

## 결정 필요

없음
