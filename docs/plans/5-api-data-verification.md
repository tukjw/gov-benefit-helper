status: done

# 구현 계획 — #5 공공서비스 혜택 API 실제 데이터 검증

브랜치: `docs/5-api-data-verification` (develop `b85b2ef` 기준)

## 목표 및 범위

- 목표: 공공서비스(혜택) API의 실제 응답을 전체 수집·집계해 `DEC-SCOPE`, `DEC-DATA`, `DEC-PROFILE`, `DEC-MATCH`의 선택지와 근거를 보고서로 남긴다.
- 포함: 호출·집계 스크립트와 SQL(`scripts/research/gov24/`), 보고서·스냅샷 목록·집계 결과(`docs/research/`), `docs/product/data-source.md` 갱신.
- 제외: 백엔드 코드, 테스트 fixture(이슈 1에서 스냅샷에서 고른다), 네 가지 결정의 확정, 호출 한도를 소진하는 실험, 원문 스냅샷 커밋.

## 확인된 사실과 제약

공식 명세(swagger)는 2026-09-28에 인증키 없이 받아 확인했다.

- 인증은 `Authorization` 헤더와 `serviceKey` 쿼리 둘 다 정의돼 있다. 헤더 값의 형식은 명세에 없어 실제 호출로 확인한다.
- `serviceDetail`·`supportConditions`의 필터는 `cond[서비스ID::EQ]` 하나이고 `page`·`perPage`를 받는다. 페이지 순회로 전체를 받을 수 있다.
- 날짜 범위 필터(`cond[수정일시::GTE]` 등)는 `serviceList`에만 있다.
- 목록은 21개, 상세는 20개 필드이며 `신청기한`·`지원대상`·`선정기준`·`지원내용`·`신청방법` 등이 겹친다.
- `supportConditions`는 48개 코드(`JA0101`~`JA2299`)가 컬럼으로 펼쳐진 형태다. 명세에는 코드 이름만 있고 값의 의미·빈 값·코드 간 조합 관계는 없다. `JA0110`·`JA0111`(대상연령)만 integer이고 나머지는 string이다.
- 코드는 개인 대상 35개(`JA01xx` 성별·연령, `JA02xx` 소득, `JA03xx` 생애·직업·건강 상태, `JA04xx` 가구)와 사업자·기업 대상 13개(`JA11xx`, `JA12xx`, `JA21xx`, `JA22xx`)로 나뉜다.
- 개발계정 트래픽은 10,000으로 안내돼 있다(`data-source.md`). 서비스마다 상세·조건을 호출하면 경로당 전체 건수만큼 호출한다.
- 머지 후 워크트리를 삭제하면 그 안의 git 무시 파일도 함께 사라진다.
- 이 개발 머신에는 `uv` 0.7.8과 Python 3.13이 있고 `duckdb` CLI는 없다.
- 백엔드 코드를 바꾸지 않으므로 `./gradlew test`는 이 작업의 검증 대상이 아니다.

## 설계 결정

1. **호출·집계는 Python 스크립트 하나와 DuckDB SQL 파일로 한다.** `uv run`이 스크립트에 적은 의존성을 설치하므로 별도 설치가 없다. 집계 로직은 `.sql` 파일에 두어 보고서의 집계 방법으로 링크한다. 셸+DuckDB CLI는 재시도·오류 처리가 투박하다. Java는 문서 이슈에 운영 코드가 들어가고 데이터를 보기 전에 수집기 구조를 정하게 된다. 로컬 MySQL은 48개 코드를 `JSON_TABLE`로 꺼내는 쿼리가 길고 Flyway가 관리할 DB와 섞인다.
2. **원문 스냅샷은 저장소 밖에 둔다.** 기본 경로는 `$XDG_DATA_HOME`(없으면 `~/.local/share`) 아래 `gov-benefit-helper/gov24/`이고 `GOV24_DATA_DIR`로 바꿀 수 있다. git에 커밋하면 force push 금지로 히스토리에서 뺄 수 없고 크기도 아직 모른다. 워크트리 안에 두면 워크트리 삭제 때 사라진다. 저장소에는 스냅샷 목록(수집 시각·경로별 건수·파일 해시)과 집계 결과만 커밋한다. 다른 사람은 새 스냅샷으로만 다시 실행할 수 있다는 한계를 보고서에 적는다.
3. **인증키는 환경 변수 `GOV24_SERVICE_KEY`로 받아 헤더로 보낸다.** 키가 URL·목록 파일·오류 출력에 섞이지 않는다. `serviceKey` 쿼리는 성공한 요청 형식을 확인하는 데만 쓴다. 저장하는 요청 기록에는 경로와 인자만 남기고, 응답 본문은 저장 전에 키 원문과 URL 인코딩 형태를 가린다.
4. **상세·조건도 페이지 순회로 전체를 받는다.** 서비스별 호출은 한도를 넘길 수 있어 하지 않는다. 모든 호출은 `--max-calls` 상한 안에서 실행하고, 상한에 닿거나 비정상 응답을 받으면 멈춘다.
5. **신청기한은 정규식 규칙으로 전체를 분류하고, 분류별 표본을 직접 확인해 오분류율을 적는다.** 표본만 직접 분류하면 규칙이 남지 않는다. LLM 분류는 다시 실행할 때 결과가 달라질 수 있다.
6. **공고 대조는 개인 대상 35개 코드만 한다.** 사업자·기업 대상 13개 코드는 값 분포만 집계하고 미확인으로 둔다. 48개 전부 대조하면 수작업이 100건을 넘는다.
7. **표본은 `md5(서비스ID || ':' || 용도)` 순으로 뽑는다.** 같은 스냅샷이면 DuckDB 버전과 관계없이 같은 표본이 나온다.
8. **착수(T0)와 마무리(T1) 두 시점에 수집한다.** 간격은 3일 이상으로 하고, 관찰 기간이 짧다는 한계를 보고서에 적는다.
   - 실행 중 변경: 두 시점 비교는 #6(1주 이상 매일 관찰)으로 옮겼다. 이 이슈는 T0만 수집한다. 비교 쿼리(`snapshot_diff.sql`)와 `--modified-since`는 가짜 스냅샷으로 동작을 확인했다.

## 단계별 구현 계획

1. 이 플랜의 `status`를 `in-progress`로 바꾼다.
2. `scripts/research/gov24/gov24.py`를 만든다. 상단에 uv 인라인 의존성(Python 3.12 이상, `duckdb` 버전 고정)을 적고 하위 명령 `probe`·`fetch`·`analyze`를 둔다.
   - `load_key()`: `GOV24_SERVICE_KEY`를 읽는다. 값을 출력하지 않는다.
   - `data_dir()`: 설계 결정 2의 경로를 돌려준다.
   - `redact(text, key)`: 키 원문과 URL 인코딩 형태를 가린다.
   - `CallBudget`: 호출 수를 세고 상한에서 멈춘다.
   - `request(path, params, auth)`: 시간 제한을 두고 호출해 상태 코드·응답 시간·본문을 돌려준다. 시간 초과·5xx에만 한 번 재시도한다.
3. `probe` 하위 명령(`probe_auth()`, `probe_page_size()`, `probe_failures()`)을 구현해 실행한다. 결과는 가린 요청 인자·상태·본문으로 `<data_dir>/probes/<시각>.json`에 저장한다.
   - 인증: 헤더와 쿼리 방식을 각각 한 번 호출한다.
   - 전체 건수와 페이지 크기: 목록 1쪽을 `perPage` 후보(100, 500, 1000, 5000)로 호출해 반환 건수·응답 크기·응답 시간을 잰다.
     - 실행 중 변경: 세 경로 모두 쟀다. 5000에서 목록·상세가 200인데 `data`를 빼고 와서 `--sizes 2000 3000 4000`으로 한 번 더 쟀다(총 39회).
       `collect()`는 `data`가 없는 200을 실패로 다룬다.
   - 실패 응답: 키 없음, 잘못된 키, `page=0`, 존재하지 않는 서비스 ID 필터를 한 번씩 호출한다.
   - 결과로 전체 수집 호출 수(3 × ⌈전체 건수 / perPage⌉)를 계산해 `--max-calls` 값을 정한다.
4. `fetch` 하위 명령(`collect(path, per_page, snapshot_dir, budget)`, `write_manifest()`)을 구현하고 T0를 수집한다.
   - 원문: `<data_dir>/snapshots/<수집 시각>/<경로>/page-0001.json`.
   - 목록 파일: 같은 폴더의 `manifest.json`. 경로별 `totalCount`·받은 건수, 페이지별 상태·크기·SHA-256·수집 시각을 담는다.
   - 목록 파일을 `docs/research/gov24-api-data/snapshots/<수집 시각>.json`에 복사해 커밋한다.
   - 실행 중 추가: T1에서 쓸 `--modified-since <값>` 옵션. 목록을 `cond[수정일시::GTE]`로 한 번 더 받아 `serviceList-modified/`에 둔다.
     조건 조회의 쪽 수는 `matchCount`로 계산한다.
5. `scripts/research/gov24/queries/`에 집계 SQL을 작성하고 `analyze` 하위 명령(`load_snapshot()`, `run_queries()`, `write_results()`)으로 실행한다. 결과는 `docs/research/gov24-api-data/results/<수집 시각>.md`에 쓴다. 모든 쿼리는 정렬 순서를 고정하고, 결과 파일에 생성 시각처럼 실행마다 바뀌는 값을 넣지 않는다.
   - `counts.sql`: 경로별 `totalCount`와 실제 행 수, 페이지 경계 중복.
   - `id_consistency.sql`: 세 경로 간 서비스 ID 누락·중복, 겹치는 필드의 값 불일치 건수와 예시.
   - `field_quality.sql`: 필드별 NULL·빈 문자열·공백 값 비율, 문자 수·바이트 수 분포, 날짜·링크 형식 패턴과 예시.
   - `condition_values.sql`: 48개 코드의 값 종류와 건수(`UNPIVOT`), 같은 그룹 코드가 동시에 채워진 건수, 대상연령 시작·종료 분포와 역전 건수.
   - `deadline_classes.sql`: 신청기한 정규식 분류(명시 날짜·상시·조건부·미확인)의 비율과 예시.
   - `region_evidence.sql`: `소관기관유형`·`소관기관명`, 상세의 `자치법규`, `지원대상` 문구에 있는 지역 단서의 분포.
   - `samples.sql`: 조건 코드 대조와 신청기한 검증용 표본(설계 결정 7).
6. 표본을 직접 확인하고 결과를 보고서 표로 남긴다.
   - 조건 코드: 개인 대상 코드마다 값이 있는 서비스 2건을 `상세조회URL`의 공고와 대조한다. 빈 값·조합·경계의 의미를 보려고 모든 코드가 빈 서비스 3건, 같은 그룹 코드가 여러 개 채워진 서비스 3건, 대상연령 경계 사례 3건을 더한다. 의미를 확인하지 못한 코드는 미확인으로 둔다.
     - 실행 중 변경: 대부분의 서비스가 그룹 전부 `Y`라, 코드별 표본은 그룹의 일부만 `Y`인 서비스에서 뽑았다(연령 제외 33개 코드 × 2건).
     - 연령은 경계 표본 3건으로 봤다. 모든 코드가 빈 서비스는 0건이라 개인 대상 코드가 전부 빈 서비스 3건으로 바꿨다.
     - 같은 그룹 복수 코드는 성별·소득·가구 전부 `Y` 3건으로 봤다. 총 75건이다.
     - 원문은 API 상세의 지원대상·선정기준·지원내용을 썼다. gov.kr 페이지 3건과 문구가 같음을 확인했다.
   - 신청기한: 분류별 10건을 원문과 대조해 오분류율을 적는다.
     - 실행 중 변경: 1차 표본에서 오분류 2건을 찾아 규칙을 고쳤다. 고친 규칙은 새 표본(`:deadline-v2`)으로 다시 검증했다.
     - 분류에는 '신청 불필요'를 더했다(분류당 10건씩 5개 분류).
7. (#6으로 옮김) T0에서 3일 이상 지난 뒤 T1을 수집하고 `analyze --snapshot <T1> --compare <T0>`로 비교한다(`snapshot_diff.sql`). 추가·사라진·변경 ID와 `수정일시` 변화를 보고, `cond[수정일시::GTE]`로 조회한 T0 이후 수정 건과 맞춰 본다.
8. `docs/research/gov24-api-data.md` 보고서를 작성한다. 확인 날짜, 표본 수, 집계 방법(실행 명령·스냅샷 목록 파일), 완료 조건별 결과, `DEC-SCOPE`·`DEC-DATA`·`DEC-PROFILE`·`DEC-MATCH`별 선택지와 근거, 한계를 적는다.
9. `docs/product/data-source.md`의 미확인 항목을 확인한 사실로 갱신하고, "첫 개발 작업" 섹션을 보고서 링크로 바꾼다.
10. 검증 명령을 실행한 뒤 이 플랜의 `status`를 `done`으로 바꾼다.

## 완료 조건 매핑

- [ ] 인증에 성공한 요청 형식, 전체 건수, 적용 가능한 페이지 크기, 전체 수집에 필요한 호출 수가 보고서에 있다 → 3, 8단계
- [ ] 세 경로 간 서비스 ID의 누락·중복·불일치 건수와 예시가 있다 → 5단계(`id_consistency.sql`), 8단계
- [ ] 주요 필드별 결측 비율과 날짜·링크·긴 텍스트의 형식 예시가 있다 → 5단계(`field_quality.sql`), 8단계
- [ ] 지원조건 코드별 실제 값 종류, 그리고 의미를 공고와 대조해 확인한 사례가 있다. 의미를 확인하지 못한 코드는 미확인으로 구분한다 → 5단계(`condition_values.sql`), 6단계
- [ ] 신청기한 표현을 명시 날짜·상시·조건부·미확인으로 나눈 비율과 예시, 대상 지역을 판별할 근거가 있는지가 있다 → 5단계(`deadline_classes.sql`, `region_evidence.sql`), 6단계
- [ ] 인증 실패 등 관찰한 실패 응답의 형식이 있다. 호출 한도를 소진하는 실험은 하지 않는다 → 3단계
- 두 시점 이상 수집해 비교하는 작업은 #6으로 옮겼다(이슈 #5 본문 수정). 보고서 8절에 T0 관찰 결과와 한계를 적었다 → 8단계
- [ ] 보고서에 확인 날짜, 표본 수, 집계 방법이 있고, 같은 스크립트로 집계를 다시 실행할 수 있다 → 8단계, 검증 명령 3
- [ ] 인증키와 인증 헤더가 저장소 파일, 보고서, 커밋 어디에도 남지 않는다 → 설계 결정 3, 검증 명령 1
- [ ] `DEC-SCOPE`, `DEC-DATA`, `DEC-PROFILE`, `DEC-MATCH` 각각에 대한 선택지와 근거가 보고서에 있다 → 8단계

## 검증 명령

저장소 루트에서 bash로 실행한다. 1·2는 출력이 없어야 한다.

```bash
# 1. 인증키가 작업 트리와 이 브랜치의 커밋에 없는지
: "${GOV24_SERVICE_KEY:?}"
ENC=$(python3 -c 'import os, urllib.parse; print(urllib.parse.quote(os.environ["GOV24_SERVICE_KEY"], safe=""))')
for k in "$GOV24_SERVICE_KEY" "$ENC"; do
  git grep --untracked -nF -e "$k"
  git log -p origin/develop..HEAD | grep -nF -e "$k"
done

# 2. 참조된 요구사항·결정 ID가 모두 정의돼 있는지
comm -23 <(grep -rhoE 'REQ-[A-Z]+-[0-9]+' docs | sort -u) \
  <(grep -hoE '^### REQ-[A-Z]+-[0-9]+' docs/product/requirements.md | cut -c5- | sort -u)
comm -23 <(grep -rhoE 'DEC-[A-Z]+' docs | sort -u) \
  <(grep -hoE '^\| DEC-[A-Z]+' docs/product/development.md | cut -c3- | sort -u)

# 3. 같은 스냅샷으로 집계를 다시 실행해도 커밋된 결과와 같은지
uv run scripts/research/gov24/gov24.py analyze --snapshot 20260928T163852
git diff --exit-code docs/research/gov24-api-data/results/
```

4. `data-source.md`에서 보고서로 가는 링크의 대상 파일이 존재하는지 확인한다.

## 결정 필요

없음
