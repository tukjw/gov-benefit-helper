# 브랜치 ruleset

`develop`·`main`의 머지 보호 규칙입니다. GitHub에 적용된 설정의 기준이며, 설정을 바꿀 때는 이 파일을 PR로 먼저 고친 뒤 머지 후 다시 적용합니다.

| 파일 | 대상 | 허용 머지 방식 | 최신 base 반영(strict) |
| --- | --- | --- | --- |
| `develop.json` | `develop` | merge commit, squash | 켬 |
| `main.json` | `main` | merge commit | 끔 |

두 규칙 모두 PR 없이 push할 수 없고, force push와 브랜치 삭제를 막습니다. GitHub Actions의 `test`(백엔드 테스트)와 `frontend` 체크가 통과해야 머지할 수 있습니다. `frontend`는 프론트 변경 감지와 검사 결과를 모은 집계 job이므로, 프론트를 바꾸지 않은 PR에서는 검사 없이 통과합니다([결정 0024](../../docs/decisions/0024-frontend-package-and-ci.md)). bypass 대상이 없으므로 저장소 관리자도 우회할 수 없습니다.

`main`에서 strict를 끄는 이유: 릴리스 머지 커밋은 `develop`에 없으므로, strict를 켜면 다음 릴리스 PR이 매번 out of date가 됩니다. CI는 PR의 머지 ref에서 실행되므로 strict 없이도 머지 결과를 검증합니다.

## 적용

저장소 루트에서 실행합니다. 처음 한 번만 생성합니다.

```sh
gh api -X POST repos/{owner}/{repo}/rulesets --input .github/rulesets/develop.json
gh api -X POST repos/{owner}/{repo}/rulesets --input .github/rulesets/main.json
```

## 갱신

```sh
gh api repos/{owner}/{repo}/rulesets --jq '.[] | "\(.id) \(.name)"'
gh api -X PUT repos/{owner}/{repo}/rulesets/<id> --input .github/rulesets/<이름>.json
```

## 확인

```sh
gh api repos/{owner}/{repo}/rules/branches/develop
gh api repos/{owner}/{repo}/rules/branches/main
```
