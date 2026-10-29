status: done

# 구현 계획 — #64 웹 프론트엔드 프로젝트 초기 구성과 PR 자동 검사 적용

브랜치: `chore/64-frontend-init` (develop `703dde5` 기준)

## 목표 및 범위

- 목표: 저장소에 Next.js 웹 프론트엔드의 기반을 두고, 프론트 정적 검사·빌드가 실패한 PR은 `develop`·`main`에 머지할 수 없게 한다(`DEC-APP`). 이후 화면 작업의 순서를 개발 문서에 둔다.
- 포함:
  - `frontend/`의 Next.js 프로젝트(App Router, TypeScript, ESLint)와 빈 첫 화면.
  - 환경 변수로 바꿀 수 있는 API 주소 설정.
  - `ci.yml`의 프론트 변경 감지·검사·집계 job과 ruleset 필수 체크 추가.
  - `development.md`의 프론트 작업 순서, `AGENTS.md`의 프론트 반영, 결정 문서 0024.
- 제외:
  - 실제 화면과 API 호출, 백엔드 CORS·Origin 허용 목록에 프론트 출처 추가. 첫 API 호출 화면 이슈에서 다룬다.
  - Tailwind 등 스타일 도구(2026-10-23 사용자 결정으로 미룸), PWA(manifest, 서비스 워커), 테스트 러너.
  - `output: 'standalone'`, 컨테이너 이미지, 프론트 배포, API 주소의 런타임 주입. O2와 `DEC-OPS`에서 정한다.
  - 프론트만 바꾼 PR에서 백엔드 `test`를 건너뛰는 것.
  - 의존성 자동 갱신(Dependabot 등).

## 확인된 사실과 제약

- 저장소에는 `backend/`만 있고, CI는 `ci.yml`의 `test` job 하나다. ruleset(`develop.json`·`main.json`)의 필수 체크는 `test`(`integration_id` 15368) 하나다. `develop`은 strict, `main`은 strict가 꺼져 있다.
- 워크플로 수준의 `on.pull_request.paths`로 실행되지 않은 필수 체크는 보고되지 않아 PR이 머지 대기 상태로 남는다. `if:`로 건너뛴 job은 필수 체크에서 통과로 취급된다. 의존 job이 실패해 건너뛰어진 job도 같다. #14가 변경 감지 방식을 제외한 이유가 이것이며, 이 이슈는 집계 job으로 해결한다(설계 3).
- `pull_request` 이벤트는 head와 base를 합친 머지 커밋을 체크아웃한다. 이 커밋의 첫 부모는 base 끝 커밋이므로 `fetch-depth: 2`로 받으면 `git diff HEAD^1 HEAD`가 이 PR이 base에 더하는 변경과 같다.
- 기존 액션은 공식 액션만 메이저 태그로 고정해 쓴다(#14 설계 8). 워크플로 권한은 `contents: read`다.
- `npm view` 기준(2026-10-23) `next`와 `create-next-app`의 최신 안정판은 16.4.2이고, `next`의 `engines`는 `node >=20.9.0`이다. 로컬 Node는 v26.10.0, npm은 11.19.1이다.
- 루트 `.gitignore`는 `.env`와 `.env.*`를 무시하고 `.env.example`만 예외로 둔다. `create-next-app`이 만드는 `.gitignore`도 `.env*`를 무시하므로 커밋할 env 파일은 `frontend/.gitignore`에서 예외로 풀어야 한다.
- `NEXT_PUBLIC_*` 값은 빌드할 때 브라우저 번들에 고정된다. `next dev`는 `.env.development`, `next build`는 `.env.production`을 읽고, `.env.local`이 둘보다 우선한다.
- 깨끗한 checkout에는 `next-env.d.ts`와 `.next/types`가 없어서, 빌드 전에 `tsc --noEmit`만 돌리면 Next 타입이 빠질 수 있다. `next typegen`으로 먼저 만든다. 2026-10-23 확인: 타입 파일 없이 `tsc --noEmit`을 돌리면 `LayoutProps`를 찾지 못해 실패한다.
- `create-next-app@16.4.2`의 기본값은 `cacheComponents: true`, `partialPrefetching: true`, ESLint 9다. AI 에이전트가 실행한 `next dev`는 `agentRules`가 꺼져 있지 않으면 `frontend/AGENTS.md`를 만든다(`next/dist/server/lib/generate-agent-files.js`).
- 생성 직후 `npm audit`은 high 5건을 보고한다. 모두 `eslint-config-next`가 쓰는 개발 의존성(`braces`)이고, 자동 수정은 `eslint-config-next@14`로 내리는 것뿐이라 적용하지 않는다.
- 백엔드는 `local` 프로필 기본값으로 `.env` 없이 실행되고, 값을 바꿀 때만 `.env.example`을 복사한다. 프론트도 같은 방식을 따른다.

## 설계 결정

1. **패키지 매니저는 npm, Node는 26으로 고정한다.** [결정 0024](../decisions/0024-frontend-package-and-ci.md)로 올린다.
   - `package-lock.json`을 커밋하고 CI는 `npm ci`로 설치한다.
   - Node 버전은 `frontend/.nvmrc`(`26`) 하나를 기준으로 두고, CI는 `setup-node`의 `node-version-file`로 같은 파일을 읽는다. `package.json`의 `engines`에도 같은 범위를 적는다.
   - pnpm은 설치가 빠르고 엄격하지만 도구가 하나 늘어서 제외했다. 앱이 하나라 workspace 기능도 필요 없다.
   - Node 24는 이미 LTS지만 로컬(v26)과 버전이 갈라져서 제외했다.
2. **프로젝트는 `create-next-app@16.4.2`로 `frontend/`에 만든다.**
   - App Router, TypeScript, ESLint를 켜고 Tailwind와 `src/` 디렉터리는 쓰지 않는다. 가능하면 `--empty`로 빈 템플릿을 만든다.
   - 생성 도구가 만든 README·안내 파일·예제 이미지는 지운다. 저장소 지침은 루트 `AGENTS.md` 하나로 둔다. `next dev`가 `frontend/AGENTS.md`를 다시 만들지 않게 `next.config.ts`에 `agentRules: false`를 둔다.
   - `cacheComponents`·`partialPrefetching`은 생성 도구 기본값(켬)을 그대로 둔다.
   - `@types/node`는 생성 기본값(`^20`) 대신 Node 26에 맞춰 `^26`으로 올린다.
   - `package.json`의 버전 범위는 생성 도구 기본값(`^`)을 따르고, 실제 설치 버전은 잠금 파일로 고정한다.
3. **CI는 변경 감지(`changes`) → 프론트 검사(`frontend-check`) → 집계(`frontend`) 구조로 하고, 집계 job만 필수 체크로 건다.** [결정 0024](../decisions/0024-frontend-package-and-ci.md)로 올린다.
   - 감지는 서드파티 액션 없이 `git diff`로 한다. 대상 경로는 `frontend/`와 `.github/workflows/ci.yml`이다.
   - 집계 job은 `if: always()`로 항상 실행된다. 감지 job이 성공했고, 프론트가 바뀌었으면 검사가 성공, 바뀌지 않았으면 검사가 skipped일 때만 통과한다. 감지가 실패해 검사가 빠졌는데 통과로 보이는 경우를 막는다.
   - 기존 `test` job과 필수 체크는 그대로 둔다. 집계 job이 `test`까지 묶으면 ruleset에서 `test`를 뺄 수 있게 되는데, 완료 조건은 `test`를 그대로 필수로 두는 것이다.
   - 매 PR 실행(필터 없음)은 단순하지만 백엔드 PR도 외부 레지스트리 장애로 막힐 수 있어 제외했다. 집계 없이 `if:`만 쓰면 감지 실패가 통과로 보여서 제외했다(2026-10-23 사용자 결정).
4. **API 주소는 `NEXT_PUBLIC_API_BASE_URL` 하나로 받고, 읽는 곳을 `frontend/lib/config.ts`의 `apiBaseUrl()` 하나로 둔다.**
   - 값이 없으면 기본값으로 대체하지 않고 오류를 낸다. 운영 빌드에 로컬 주소가 들어가는 것을 막기 위해서다.
   - 모듈을 불러올 때가 아니라 호출할 때 검사한다. 아직 API를 쓰지 않는 화면의 빌드가 값 없이도 통과해야 하기 때문이다.
   - 로컬 기본값(`http://localhost:8080`)은 커밋하는 `frontend/.env.development`에 둔다. 바꿀 때는 `frontend/.env.example`을 `.env.local`로 복사한다. 백엔드의 `local` 프로필 기본값과 `.env.example`과 같은 방식이다.
   - 빌드할 때 값이 고정되므로 환경마다 다시 빌드해야 한다. 런타임 주입이 필요한지는 O2에서 배포 방식과 함께 정한다.
5. **프론트 작업은 `development.md`에 F0~F4로 둔다.**
   - F0이 이 이슈이고, F1~F4는 각각 검색·상세 공개 화면, 가입·로그인, 프로필·관심 혜택·조건 비교, 비밀번호 재설정·탈퇴다.
   - 비밀번호 재설정과 탈퇴도 `REQ-ACCOUNT-01`의 P0이고 첫 공개는 2차 MVP(`DEC-SCOPE`)이므로, F1~F4는 모두 첫 공개 전에 마친다.
   - 프론트 배포는 별도 행을 두지 않고 O2 범위에 넣는다. 배포 위치는 `DEC-OPS`에서 정한다(2026-10-23 사용자 결정).

## 단계별 구현 계획

1. 프론트 프로젝트를 만든다.
   - 저장소 루트에서 `npx create-next-app@16.4.2 frontend --ts --eslint --app --no-tailwind --no-src-dir --no-react-compiler --import-alias "@/*" --use-npm --disable-git --empty --no-agents-md --no-agent-feedback --yes`를 실행한다.
   - `frontend/app/page.tsx`는 서비스 이름 정도만 보이는 빈 첫 화면으로 정리하고, `frontend/app/layout.tsx`의 `metadata`와 `lang`을 서비스에 맞춘다(`lang="ko"`).
   - 생성된 `README.md`를 지우고, `frontend/next.config.ts`에 `agentRules: false`를 추가한다.
2. Node 버전과 스크립트를 고정한다.
   - `frontend/.nvmrc`에 `26`을, `frontend/package.json`의 `engines.node`에 `>=26`을 적고, `@types/node`를 `^26`으로 올린다.
   - `package.json`의 `scripts`에 `dev`, `build`, `start`, `lint`(`eslint`), `typecheck`(`next typegen && tsc --noEmit`)를 둔다.
   - `npm install`로 만든 `frontend/package-lock.json`을 커밋한다.
3. API 주소 설정을 추가한다.
   - `frontend/lib/config.ts`: `apiBaseUrl()`이 `process.env.NEXT_PUBLIC_API_BASE_URL`을 읽어 끝의 `/`를 떼고 돌려준다. 값이 비어 있으면 변수 이름을 담은 `Error`를 던진다.
   - `frontend/.env.development`: `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`.
   - `frontend/.env.example`: 같은 변수와 용도 주석.
   - `frontend/.gitignore`에 `!.env.example`, `!.env.development`를 추가한다.
4. `.github/workflows/ci.yml`에 job 세 개를 추가한다. `test` job은 바꾸지 않는다.
   - `changes`
     - `actions/checkout@v7`(`fetch-depth: 2`)으로 받는다.
     - `git diff --name-only HEAD^1 HEAD`가 `^frontend/` 또는 `^\.github/workflows/ci\.yml$`에 맞는 경로를 포함하면 출력 `frontend=true`, 아니면 `false`를 낸다.
   - `frontend-check`
     - `needs: changes`, `if: needs.changes.outputs.frontend == 'true'`, `working-directory: frontend`, `timeout-minutes: 10`.
     - 단계: `actions/checkout@v7` → `actions/setup-node`(작업 시점의 최신 메이저 태그. `node-version-file: frontend/.nvmrc`, `cache: npm`, `cache-dependency-path: frontend/package-lock.json`) → `npm ci` → `npm run lint` → `npm run typecheck` → `npm run build`.
     - 빌드 단계에는 `NEXT_PUBLIC_API_BASE_URL: http://localhost:8080`을 준다. 나중에 빌드 중 값을 쓰는 화면이 생겨도 CI가 운영 주소 없이 빌드하게 하기 위해서다.
   - `frontend`(집계)
     - `needs: [changes, frontend-check]`, `if: always()`, `timeout-minutes: 5`.
     - `needs.changes.result`, `needs.changes.outputs.frontend`, `needs.frontend-check.result`를 환경 변수로 받는다. 통과 조건은 설계 3과 같고, 실패할 때는 세 값을 `::error::`로 남긴다.
   - `test` job 위의 주석을 필수 체크 이름이 `test`와 `frontend` 두 개라는 내용으로 고친다.
5. ruleset에 필수 체크를 추가한다.
   - `.github/rulesets/develop.json`, `.github/rulesets/main.json`의 `required_status_checks`에 `{ "context": "frontend", "integration_id": 15368 }`를 추가한다.
   - `.github/rulesets/README.md`의 필수 체크 설명을 `test`와 `frontend` 두 개로 고치고, `frontend`가 집계 job이라는 점을 적는다.
6. `docs/product/development.md`를 고친다.
   - `### 운영 작업` 다음에 `### 프론트 작업` 절과 F0~F4 표를 추가한다. 열은 운영 작업 표와 같다.
     - F0: 이 이슈.
     - F1 검색·상세 공개 화면: 2 선행, 서버 렌더링(`DEC-APP`).
     - F2 가입·로그인: 3 선행.
     - F3 프로필·관심 혜택·조건 비교: 4 선행, 브라우저 렌더링.
     - F4 비밀번호 재설정·탈퇴: 3 선행.
   - 첫 공개(2차 MVP)에는 기능 0~4번, F1~F4, O2가 필요하다는 문장을 넣는다.
   - O2 행에 프론트 배포를 포함한다는 점과 배포 위치는 `DEC-OPS`에서 정한다는 점을 적는다.
7. `AGENTS.md`를 고친다.
   - 저장소 구성: `frontend/`(Next.js 웹. npm 명령은 `frontend/`에서 실행).
   - 로컬 실행: `frontend/`에서 `npm ci` → `npm run dev`로 실행하면 `http://localhost:3000`에서 열린다. API 주소는 `.env.development`의 기본값을 쓰고, 바꿀 때만 `.env.example`을 `.env.local`로 복사한다. Node 버전은 `frontend/.nvmrc`를 따른다.
   - 검증: `frontend/`에서 `npm run lint`, `npm run typecheck`, `npm run build`가 통과해야 한다. CI 필수 체크는 `test`와 `frontend`이다. 의존성을 바꾸면 `package-lock.json`을 함께 커밋한다.
8. 결정 문서 `docs/decisions/0024-frontend-package-and-ci.md`를 템플릿으로 작성하고, `docs/decisions/README.md`의 결정 목록 표에 추가한다.
9. PR 설명에 머지 후 사용자가 실행할 명령과 검증 순서를 적는다.
   - ruleset 갱신: `gh api -X PUT repos/{owner}/{repo}/rulesets/<id> --input .github/rulesets/<이름>.json`(develop, main).
   - 검증 PR: 프론트에 lint 오류를 넣은 PR과 백엔드만 바꾼 PR을 각각 연다. 앞의 PR이 막히고 뒤의 PR이 막히지 않는지 확인한 뒤 둘 다 머지하지 않고 닫는다.

## 완료 조건 매핑

- [ ] 문서의 로컬 실행 절차대로 프론트를 띄우면 첫 화면이 열린다 → 1·2·7단계. `AGENTS.md` 절차대로 `npm ci` → `npm run dev` 후 `http://localhost:3000` 응답 확인
- [ ] API 주소는 환경 설정으로 바꿀 수 있고, 운영 주소나 비밀값이 코드에 하드코딩되어 있지 않다 → 3단계. `apiBaseUrl()`의 값 변경과 미설정 오류 확인, 운영 도메인 grep
- [ ] 프론트 정적 검사나 빌드가 실패하는 PR은 `develop`과 `main`에 머지할 수 없다. 이 확인은 머지하지 않고 닫는다 → 4·5·9단계. ruleset 적용 후 검증 PR로 확인, `gh api repos/{owner}/{repo}/rules/branches/main`
- [ ] 백엔드만 바꾼 PR이 프론트 검사 때문에 머지가 막히지 않고, 기존 백엔드 `test` 체크도 그대로 필수로 남는다 → 4·5·9단계. 백엔드만 바꾼 검증 PR에서 `frontend-check` skipped와 `frontend` 통과 확인, ruleset의 `test` 유지
- [ ] 의존성 잠금 파일이 커밋되어 CI와 로컬이 같은 버전으로 설치된다 → 2·4단계. `package-lock.json` 커밋과 CI의 `npm ci`
- [ ] `development.md`에 프론트 작업 순서가, `AGENTS.md`에 프론트 실행·검증 방법이 반영되어 있다 → 6·7단계

## 검증 명령

- `frontend/`에서 `npm ci && npm run lint && npm run typecheck && npm run build`
- `frontend/`에서 `npm run dev` 후 `curl -sf http://localhost:3000 >/dev/null`
- `frontend/`에서 `NEXT_PUBLIC_API_BASE_URL=https://api.example.test/ node -e "import('./lib/config.ts').then(m => console.log(m.apiBaseUrl()))"`가 `https://api.example.test`를 출력하고, 변수 없이 실행하면 오류가 나는지 확인(Node 26의 TypeScript 타입 제거 실행)
- `grep -rn "govbenefit.kr" frontend --exclude-dir=node_modules --exclude-dir=.next` 결과 없음
- `git check-ignore -v frontend/.env.development frontend/.env.example`이 무시되지 않고, `frontend/.env.local`은 무시되는지 확인
- 집계 job 판정 스크립트를 (감지 성공·true·success), (성공·false·skipped)로 실행하면 통과하고, (실패·빈 값·skipped), (성공·true·failure), (성공·true·skipped), (성공·false·success)로 실행하면 실패하는지 확인
- `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest`
- `backend/`에서 `./gradlew test`
- 이 이슈의 PR에서 `test`·`changes`·`frontend-check`·`frontend`가 모두 통과하는지 확인

## 결정 필요

없음
