# 메일 발신 도메인 인증과 발송 설정

계정 메일(가입 인증번호·비밀번호 재설정)을 Resend로 보내기 위한 준비 절차다. 발송 구조는 [결정 0032](../decisions/0032-account-mail-resend.md), 서비스 선택은 [`DEC-OPS(메일 발송)`](../product/development.md#결정한-사항)에 있다. Resend 대시보드와 DNS는 운영자가 직접 바꾼다. API 키와 DNS 레코드 값은 이 문서와 저장소에 적지 않는다.

절차는 2026-10-27에 Resend 문서([Add a domain](https://resend.com/docs/add-a-domain), [API 소개](https://resend.com/docs/api-reference/introduction))로 확인했다. 대시보드 화면이 바뀌었으면 Resend 문서를 따른다.

## 1. 발신 도메인 추가

1. Resend에 가입하고 대시보드의 **Domains**에서 **Add Domain**을 누른다.
2. 발신 도메인 `govbenefit.kr`을 입력한다.
   - Resend는 발신 평판을 분리하려고 하위 도메인(예: `notifications.govbenefit.kr`)을 권장한다. 하위 도메인을 쓰면 발신 주소(`RESEND_FROM`)도 그 도메인의 주소여야 한다.
3. 발송 리전은 수신자가 많은 곳에서 가까운 리전(예: 도쿄)을 고른다.
4. Return-Path 하위 도메인은 기본값(`send`)을 둔다.

## 2. DNS 레코드 추가

도메인의 **Records** 탭이 보여 주는 레코드를 도메인 DNS 관리 화면에 그대로 추가한다. 이름과 값은 도메인마다 다르므로 대시보드에서 복사한다.

| 종류 | 이름 | 용도 |
| --- | --- | --- |
| TXT | 대시보드가 보여 주는 이름(DKIM) | 메일 서명 |
| TXT | Return-Path 하위 도메인(기본 `send`) | SPF |
| MX | Return-Path 하위 도메인(기본 `send`) | 반송 처리 |

- DNS 서비스가 프록시를 지원하면(예: Cloudflare) 레코드의 프록시를 끈다. 켜 두면 인증이 끝나지 않는다.
- 보통 15분 안에 인증되고, DNS 전파에 최대 72시간이 걸린다. 72시간이 지나도 인증되지 않으면 대시보드에서 **Restart verification**을 누른다.
- 인증된 뒤 `_dmarc` TXT 레코드(DMARC)를 더하면 받은편지함 도달률이 좋아진다. 값은 Resend의 DMARC 안내를 따른다.

## 3. API 키 만들기

대시보드의 **API Keys**에서 키를 만든다. 권한은 **Sending access**, 도메인은 위에서 인증한 도메인으로 한정한다. 키는 만들 때 한 번만 보이므로 바로 실행 환경에 넣는다.

## 4. 실행 환경 설정

API 서버의 실행 환경(환경 변수 또는 `backend/.env`)에 넣는다. 형식은 `backend/.env.example`에 있다.

| 변수 | 값 |
| --- | --- |
| `MAIL_SENDER` | `resend` |
| `RESEND_API_KEY` | 3에서 만든 키 |
| `RESEND_FROM` | 인증한 도메인의 발신 주소. 예: `정부 혜택 도우미 <no-reply@govbenefit.kr>` |

- `MAIL_SENDER`가 비어 있으면 발송 구현이 없어 기동에 실패한다. `resend`인데 `RESEND_API_KEY`나 `RESEND_FROM`이 비어 있어도 기동에 실패한다.
- `local` 프로필은 `application-local.yaml`의 `auth.mail.sender: logging`이 `.env` 값보다 우선한다. 로컬에서 실제 발송을 확인할 때는 환경 변수 `AUTH_MAIL_SENDER=resend`로 덮는다.

## 5. 수신 확인

로컬에서 확인하는 예다. `backend/.env`에 `RESEND_API_KEY`, `RESEND_FROM`을 넣고 `backend/`에서 실행한다.

```sh
SPRING_PROFILES_ACTIVE=local AUTH_MAIL_SENDER=resend ./gradlew bootRun
curl -s -i -X POST localhost:8080/api/auth/email-verification -H 'Content-Type: application/json' \
  -d '{"email":"<받을 주소>"}'
```

1. 응답이 204인지 본다. 발송은 응답 뒤에 따로 일어나므로, 발송에 실패해도 204다.
2. 받은편지함(스팸함 포함)에서 `[정부 혜택 도우미] 가입 인증번호` 메일과 발신 주소를 확인한다.
3. 메일의 번호로 확인 API가 성공하는지 본다.
   ```sh
   curl -s -X POST localhost:8080/api/auth/email-verification/confirm -H 'Content-Type: application/json' \
     -d '{"email":"<받을 주소>","code":"<번호>"}'
   ```
4. 메일이 오지 않으면 앱 로그에서 `가입 인증번호 메일을 보내지 못했습니다`와 그 뒤의 상태 코드를 찾는다. 로그에는 행 ID만 있다. 대시보드의 **Emails**에서 전달 상태를 본다.
   - `Resend 응답 상태 401`·`403`: API 키가 없거나 틀렸거나, 발신 주소의 도메인이 아직 인증되지 않았다.
   - `Resend 응답 상태 422` 등 그 밖의 4xx: 발신 주소 형식처럼 요청 내용이 틀렸다.
   - `Resend 응답 상태 429`: 초당 요청 제한이나 무료 플랜의 하루 발송 한도(100건)를 넘었다.
