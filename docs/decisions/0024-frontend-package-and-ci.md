# 0024. 프론트엔드 패키지 관리와 PR 검사 구조

- 상태: 채택
- 날짜: 2026-10-23
- 출처: #64, [플랜](../plans/64-frontend-init.md)

## 배경

`DEC-APP`에 따라 Next.js 웹 프론트를 `frontend/`에 추가했다. CI와 로컬이 같은 의존성으로 설치되어야 하고, 프론트 검사·빌드가 실패한 PR은 머지를 막아야 한다. 동시에 백엔드만 바꾼 PR은 프론트 검사 때문에 막히면 안 된다.

GitHub는 워크플로 수준의 `paths` 필터로 실행되지 않은 필수 체크를 보고하지 않아 PR이 머지 대기로 남는다. 반대로 `if:`로 건너뛴 job은 필수 체크에서 통과로 본다. 의존 job이 실패해 건너뛰어진 job도 마찬가지다. #14는 이 때문에 변경 감지 방식을 쓰지 않았다.

## 결정

프론트 의존성은 npm으로 관리하고 `package-lock.json`을 커밋한다. Node 버전은 `frontend/.nvmrc` 하나를 기준으로 둔다. CI는 변경 감지(`changes`) → 프론트 검사(`frontend-check`) → 집계(`frontend`) 순서로 돌고, 집계 job `frontend`만 필수 체크로 건다.

- `changes`는 서드파티 액션 없이 PR 머지 커밋과 첫 부모의 `git diff`로 `frontend/`와 `.github/workflows/ci.yml`의 변경을 감지한다.
- `frontend-check`는 프론트가 바뀐 PR에서만 `npm ci` → `npm run lint` → `npm run typecheck` → `npm run build`를 실행한다.
- `frontend`는 `if: always()`로 항상 실행된다. `changes`가 성공했고, 프론트가 바뀌었으면 검사가 success, 바뀌지 않았으면 skipped일 때만 통과한다.
- 백엔드 `test` 체크는 별도 필수 체크로 그대로 둔다.

## 근거

- npm은 Node에 포함되어 도구가 늘지 않는다. pnpm은 설치가 빠르고 엄격하지만 도구 설치와 버전 고정이 하나 더 필요하다. 앱이 하나라 workspace 기능도 필요 없다.
- Node 26은 로컬 개발 환경(v26.10.0)과 같다. `next@16.4.2`의 `engines`는 `node >=20.9.0`이다. CI의 `setup-node`는 `node-version-file`로 같은 `.nvmrc`를 읽는다.
- 필터 없이 매 PR 실행하는 방식은 단순하지만, 백엔드만 바꾼 PR도 npm 레지스트리 장애 같은 외부 요인으로 막힐 수 있다.
- 집계 없이 `if:`만 쓰면 `changes`가 실패해도 검사 job이 skipped가 되어 통과로 보인다. 집계 job은 이 경우를 실패로 바꾼다. 판정 스크립트를 결과 조합 7가지로 실행해 통과 조합 2가지만 0을 반환하는지 확인했다(2026-10-23).
- 집계 job이 `test`까지 묶으면 ruleset에서 `test`를 뺄 수 있게 된다. 백엔드 검사를 독립 필수 체크로 두려고 범위를 프론트로 한정했다.
- `pull_request`의 머지 커밋은 첫 부모가 base 끝이므로 `fetch-depth: 2`로 받아 비교하면 이 PR이 base에 더하는 변경만 나온다. 서드파티 감지 액션(`dorny/paths-filter`)은 공식 액션만 쓰는 기존 방침(#14)과 맞지 않아 제외했다.
- 깨끗한 checkout에는 Next가 만드는 타입 파일(`next-env.d.ts`, `.next/types`)이 없어 `tsc --noEmit`만 돌리면 `LayoutProps`를 찾지 못하고 실패한다(2026-10-23 확인). 그래서 `typecheck`는 `next typegen`을 먼저 실행한다.

## 적용 위치

- `frontend/package.json`, `frontend/package-lock.json`, `frontend/.nvmrc`
- `.github/workflows/ci.yml`: `changes`, `frontend-check`, `frontend` job
- `.github/rulesets/develop.json`, `.github/rulesets/main.json`: 필수 체크 `test`, `frontend`
- `AGENTS.md`: 저장소 구성·로컬 실행·검증 절

## 영향

- 프론트 검사 단계를 추가하거나 바꿀 때는 `frontend-check`를 고친다. 집계 job과 ruleset은 그대로 둔다.
- 감지 경로 밖의 파일(예: 루트 설정 파일)이 프론트 빌드에 영향을 주게 되면 `changes`의 경로 패턴에 추가한다.
- 필수 체크 이름(`frontend`)을 바꾸면 ruleset 두 파일을 함께 고치고 다시 적용한다.
- 다른 패키지 매니저로 바꾸거나 프론트 앱이 여럿이 되면 이 결정을 대체한다.
