status: done

# 구현 계획 — #18 여러 이슈에 걸치는 기술 설계 결정을 별도 문서로 기록

브랜치: `docs/18-tech-decision-records` (develop `c7fff84` 기준)

## 목표 및 범위

- 목표: 이슈가 끝난 뒤에도 지켜야 하는 기술 결정을 한곳에서 찾고, 결정이 바뀌어도 이전 기록과 현재 유효한 결정을 함께 추적할 수 있게 한다.
- 포함: `docs/decisions/`의 `README.md`·`_template.md`·결정 문서 2개, `docs/README.md`와 `docs/plans/README.md`의 안내, `docs/plans/1-backend-init.md`의 링크.
- 제외: 제품 결정(`DEC-*`)의 이동, `AGENTS.md` 수정(`AGENTS.md`가 `docs/README.md`를 안내하므로 그 경로로 찾아갈 수 있다), 설정·코드 변경.

## 확인된 사실과 제약

- 기술 결정은 지금 플랜의 `설계 결정` 항목에만 있다. 제품 결정은 `docs/product/development.md`의 `DEC-*` 표에 있다.
- #1 플랜의 설계 결정 5개는 다음과 같이 나뉜다.
  - 1번 Testcontainers MySQL·Docker 필수, 5번 `local` 기본 계정: `AGENTS.md`에 이미 규칙으로 있다.
  - 4번 `.env` import 방식: #1의 구성 방법에 그치고 이후 이슈가 따라야 할 제약이 아니다.
  - 2번 정렬 규칙, 3번 시간대: 앞으로 만들 테이블, 시각 컬럼, 수집 스케줄러, 검색 동작이 따라야 한다.
- 정렬 규칙 `utf8mb4_0900_ai_ci`는 `docker-compose.local.yml`의 `--collation-server`와 `TestcontainersConfiguration.java`의 `--collation-server`에 명시되어 있다.
- 시간대 `Asia/Seoul`은 세 곳에 맞춰져 있다: 로컬 compose와 테스트 컨테이너의 `TZ`, `application.yaml`의 JDBC `serverTimezone`, `hibernate.jdbc.time_zone`.
- `docs/README.md`에 "과거 플랜을 현재 제품 명세처럼 덮어쓰지 않는다"는 원칙이 있다. `1-backend-init.md`는 `status: done`이다.
- 코드 변경이 없어 `./gradlew test`는 이 작업의 검증 대상이 아니다.

## 설계 결정

1. **결정 문서는 `docs/decisions/NNNN-<슬러그>.md`로, 결정 하나에 파일 하나를 둔다.** 번호는 네 자리 순번이며 재사용하지 않는다. `DEC-*`처럼 주제 이름을 ID로 쓰면 같은 주제의 결정이 바뀔 때 이전 결정과 새 결정을 구분하기 어렵다.
2. **채택된 결정 문서의 본문은 고치지 않는다.** 결정이 바뀌면 새 문서를 만들어 `대체: NNNN`을 적고, 기존 문서는 `상태` 줄만 `대체됨 → NNNN`으로 바꾼다. 오탈자와 깨진 링크 수정은 예외로 한다.
3. **현재 유효한 결정은 `docs/decisions/README.md`의 목록 표로 찾는다.** 표에는 번호, 제목, 상태, 출처 이슈를 둔다. 결정 문서를 추가하거나 대체할 때 같은 커밋에서 표를 고친다.
4. **결정 문서로 올리는 기준은 "결정을 내린 이슈가 끝난 뒤에도 다른 이슈의 코드·스키마·설정이 따라야 하는가"다.** 그 이슈 안에서만 의미가 있는 결정은 플랜에 남긴다. `AGENTS.md`에 규칙으로 있는 결정은 올리지 않는다. 제품 동작에 관한 결정은 기존대로 `DEC-*`로 둔다.
5. **#1 플랜의 결정 2·3번은 본문을 지우지 않고 문장 끝에 결정 문서 링크를 붙인다.** 플랜은 당시 기록이므로 내용을 보존하고, 현재 유효한 결정은 링크를 따라가 확인하게 한다.

## 단계별 구현 계획

1. 이 플랜의 `status`를 `in-progress`로 바꾼다.
2. `docs/decisions/README.md`를 추가한다.
   - 이 디렉터리가 답하는 질문, 플랜·`DEC-*`·`AGENTS.md`와의 관계
   - 올리는 기준(설계 결정 4)
   - 파일명·번호 규칙, 상태 값(`채택`, `대체됨 → NNNN`), 본문 불변과 대체 절차(설계 결정 1·2)
   - 결정 목록 표(설계 결정 3)
3. `docs/decisions/_template.md`를 추가한다. 항목: `상태`, `날짜`, `출처`(이슈·플랜 링크), `대체`(있을 때만), 배경, 결정, 근거, 적용 위치, 영향(앞으로 따라야 할 작업).
4. `docs/decisions/0001-db-collation.md`를 추가한다.
   - 결정: 문자 집합 `utf8mb4`, 정렬 규칙 `utf8mb4_0900_ai_ci`를 로컬 DB와 테스트 DB에 명시한다.
   - 근거: `utf8mb4_unicode_ci`는 이모지를 모두 같은 값으로 비교하고 끝 공백을 무시한다. 기본값과 같아도 환경 간 일치를 드러내기 위해 명시한다. 이미 만든 DB는 서버 옵션을 바꿔도 기본 정렬 규칙이 바뀌지 않는다.
   - 적용 위치: `docker-compose.local.yml`, `TestcontainersConfiguration.java`
   - 영향: 새 테이블과 문자열 컬럼, 이름 검색·정렬
5. `docs/decisions/0002-db-timezone.md`를 추가한다.
   - 결정: DB 서버, JDBC 세션, Hibernate 시각 변환을 모두 `Asia/Seoul`로 맞춘다.
   - 근거: JDBC `serverTimezone`은 서버 세션 시간대를 바꾸지 않고, 시간대를 지정하지 않은 MySQL 컨테이너는 UTC로 뜬다.
   - 적용 위치: `docker-compose.local.yml`과 `TestcontainersConfiguration.java`의 `TZ`, `application.yaml`의 `serverTimezone`과 `hibernate.jdbc.time_zone`
   - 영향: 시각 컬럼, 수집 스케줄러
6. `docs/plans/1-backend-init.md`의 설계 결정 2·3번 끝에 각각 `0001`, `0002` 링크를 붙인다.
7. `docs/README.md`를 고친다. 첫 문단에 `decisions/`의 역할을 추가하고, "먼저 읽을 문서" 표에 `decisions/README.md`를 넣는다. "문서 사용 방법" 4번에 "여러 이슈에 걸치는 기술 결정이 바뀌면 새 결정 문서로 대체한다"를 덧붙인다.
8. `docs/plans/README.md`에 "설계 결정과 결정 문서" 절을 추가한다. 올리는 기준을 적고, 필수 항목 `설계 결정`에 "결정 문서로 올린 결정은 링크한다"를 덧붙인다.
9. 검증 명령을 실행한 뒤 이 플랜의 `status`를 `done`으로 바꾼다.

## 완료 조건 매핑

- [ ] `docs/decisions/`에 작성 규칙과 템플릿이 있고, `docs/README.md`에서 찾아갈 수 있다 → 2·3·7단계
- [ ] 결정을 별도 문서로 올릴지 플랜에 남길지 판단 기준이 `docs/plans/README.md`에 적혀 있다 → 8단계
- [ ] 결정이 바뀌어도 기존 결정 문서의 내용이 덮어써지지 않고, 어떤 결정이 이를 대체했는지 따라갈 수 있다 → 2·3단계(설계 결정 2·3)
- [ ] #1의 정렬 규칙과 시간대 결정이 각각 결정 문서로 있고, `docs/plans/1-backend-init.md`에서 해당 문서로 링크된다 → 4·5·6단계
- [ ] `AGENTS.md`에 이미 규칙으로 있는 결정(Testcontainers 필수, `local` 기본 계정)은 결정 문서로 중복 기록되지 않는다 → 2단계의 기준, 검증 명령 2
- [ ] 문서 안의 결정 문서 링크가 모두 실제 파일을 가리킨다 → 검증 명령 1

## 검증 명령

1. `docs/`와 `AGENTS.md`에서 `decisions/`를 가리키는 마크다운 링크를 모두 뽑아, 각 링크를 해당 문서 위치 기준으로 풀었을 때 파일이 있는지 확인한다.
   ```sh
   grep -rnoE '\]\([^)]*decisions/[^)#]*' docs AGENTS.md
   ```
2. `ls docs/decisions/`로 결정 문서가 `0001`, `0002` 두 개뿐이고 Testcontainers·`local` 계정 문서가 없는지 확인한다.
3. `docs/decisions/README.md`의 목록 표와 실제 결정 문서 파일, 각 문서의 `상태` 줄이 일치하는지 확인한다.

## 결정 필요

없음
