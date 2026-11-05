# 메일 발신 도메인 인증과 발송 설정

계정 메일(가입 인증번호·비밀번호 재설정)을 Amazon SES로 보내기 위한 준비 절차다. 발송 구조는 [결정 0045](../decisions/0045-account-mail-ses.md), 서비스 선택은 [`DEC-OPS(메일 발송)`](../product/development.md#결정한-사항)에 있다. AWS 콘솔과 DNS는 운영자가 직접 바꾼다. 접근 키와 DNS 레코드 값은 이 문서와 저장소에 적지 않는다.

절차는 2026-11-05에 AWS 문서로 확인했다. 콘솔 화면이 바뀌었으면 AWS 문서를 따른다.

- [도메인 인증(Easy DKIM)](https://docs.aws.amazon.com/ses/latest/dg/send-email-authentication-dkim-easy.html)
- [샌드박스 해제](https://docs.aws.amazon.com/ses/latest/dg/request-production-access.html)
- [계정 수준 억제 목록](https://docs.aws.amazon.com/ses/latest/dg/sending-email-suppression-list.html)
- [이메일 피드백 전달](https://docs.aws.amazon.com/ses/latest/dg/monitor-sending-activity-using-notifications-email.html)
- [사용자 지정 MAIL FROM](https://docs.aws.amazon.com/ses/latest/dg/mail-from.html)(지금은 쓰지 않는다)

## 1. 리전

SES 콘솔 오른쪽 위에서 **아시아 태평양(서울) `ap-northeast-2`** 를 고른다. 도메인 인증, 샌드박스 해제, 억제 목록, 발송 할당량은 모두 리전마다 따로라 아래 작업은 모두 이 리전에서 한다. 실행 환경의 `SES_REGION`도 같은 값이어야 한다.

## 2. 발신 도메인 인증

1. **Configuration → Identities → Create identity**에서 **Domain**을 고르고 `mail.govbenefit.kr`을 입력한다.
   - 발신 평판을 서비스의 다른 메일과 분리하려고 하위 도메인을 쓴다. 발신 주소(`SES_FROM`)도 이 도메인의 주소여야 한다.
2. **Verifying your domain**에서 **Easy DKIM**, 키 길이 **RSA_2048_BIT**를 고른다. **DKIM signatures**는 켠 채로 둔다.
3. 사용자 지정 MAIL FROM은 쓰지 않는다. 반송은 SES 기본 MAIL FROM(`amazonses.com`)으로 받아 SES가 처리한다.
4. 만든 뒤 콘솔이 보여 주는 CNAME 레코드 3개를 도메인 DNS 관리 화면에 그대로 넣는다. 이름은 `<토큰>._domainkey.mail.govbenefit.kr`, 값은 `<토큰>.dkim.amazonses.com` 형식이며 콘솔에서 복사한다.
   - DNS가 Cloudflare면 레코드의 프록시를 끈다(DNS only). 켜 두면 인증이 끝나지 않는다.
   - 보통 몇 분 안에, 길게는 72시간 안에 **Identity status**가 Verified가 된다.
5. DMARC: `_dmarc.mail.govbenefit.kr`(없으면 `_dmarc.govbenefit.kr`)에 TXT 레코드를 둔다. 예: `v=DMARC1; p=none; rua=mailto:<받을 주소>`. MAIL FROM이 `amazonses.com`이라 SPF는 발신 도메인과 맞지 않지만, DKIM이 맞으므로 DMARC는 통과한다.
6. 도메인의 **Notifications** 탭에서 **Email feedback forwarding**이 Enabled인지 확인한다(기본값). 반송·수신 거부 신고 알림이 발신 주소(`help@mail.govbenefit.kr`)로 메일로 온다. 이 주소는 Cloudflare 이메일 라우팅으로 운영자 메일함에 전달되므로 그 라우팅 규칙을 지우지 않는다. 피드백 전달을 끄려면 SNS 알림을 대신 설정해야 한다.

## 3. 발송 전용 IAM 사용자와 접근 키

1. IAM 콘솔의 **Users → Create user**에서 `govbenefit-ses-sender`를 만든다. **콘솔 접근 권한은 주지 않는다**.
2. 권한은 **Attach policies directly**를 고르지 않고, 만든 뒤 **Add permissions → Create inline policy**에서 JSON으로 아래 정책 하나만 붙인다. `<계정 ID>`는 콘솔 오른쪽 위의 12자리 계정 ID다.

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Action": "ses:SendEmail",
         "Resource": "arn:aws:ses:ap-northeast-2:<계정 ID>:identity/mail.govbenefit.kr"
       }
     ]
   }
   ```

3. 사용자의 **Security credentials → Create access key**에서 용도 **Application running outside AWS**를 고른다. 비밀 키는 만들 때 한 번만 보이므로 바로 실행 환경(4)에 넣는다.
4. 키를 바꿀 때는 새 키를 만든다 → 실행 환경에 넣고 배포한다 → 메일이 오는지 확인한다(6) → 옛 키를 **Deactivate** → 문제가 없으면 **Delete** 순서로 한다. IAM 사용자는 접근 키를 2개까지 가질 수 있다.

## 4. 계정 수준 억제 목록

**Configuration → Suppression list**의 **Account-level settings**에서 **Suppression list**가 Enabled, 사유가 **Bounces and complaints**인지 확인한다. 2019-11-25 이후 만든 계정은 기본값이 이렇다. 아니면 **Edit**에서 켠다.

- 목록의 주소로 보내면 SES가 요청을 받아들이지만 실제로 보내지 않는다. 앱 로그에는 실패가 남지 않는다.
- 주소는 지울 때까지 남는다. 이용자가 삭제를 요구하거나 잘못 오른 주소는 목록에서 주소를 골라 **Remove**하거나 `aws sesv2 delete-suppressed-destination --email-address <주소> --region ap-northeast-2`로 지운다. 목록은 대소문자를 구분하므로 목록에 보이는 그대로 쓴다.
- Gmail은 수신 거부 신고를 SES에 알려 주지 않으므로 Gmail 사용자의 신고는 목록에 오르지 않는다.

## 5. 샌드박스 해제

새 계정과 새 리전은 샌드박스다. 인증한 주소·도메인과 메일박스 시뮬레이터에만 보낼 수 있고, 24시간에 200통, 초당 1통이다. 서울 리전은 2026-11-05에 해제되었다. 다른 리전이나 계정에서 다시 할 때 아래를 따른다.

1. **Account dashboard**의 샌드박스 안내에서 **View Get set up page → Request production access**를 누른다.
2. 메일 유형은 **Transactional**, 웹사이트 URL은 `https://govbenefit.kr`, 추가 연락처를 넣는다.
3. 가입한 이용자가 요청할 때만 보내는 계정 메일이고 반송·신고는 억제 목록으로 막는다는 점을 확인하고 제출한다.
4. AWS Support가 96시간 안에 처음 답한다. 해제 뒤 **Account dashboard**에서 하루 발송 할당량과 초당 속도를 확인한다. 늘려야 하면 같은 화면에서 증설을 요청한다.

승인 전에는 실제 이용자에게 메일이 가지 않으므로 운영에 배포하지 않는다.

## 6. 실행 환경 설정

API 서버의 실행 환경에 넣는다. 운영은 app VM의 `app.env`(형식은 `deploy/app/app.env.example`, 절차는 [운영 배포](deploy.md)), 로컬은 `backend/.env`(형식은 `backend/.env.example`)다.

| 변수 | 값 |
| --- | --- |
| `MAIL_SENDER` | `ses` |
| `SES_ACCESS_KEY_ID` | 3에서 만든 접근 키 ID |
| `SES_SECRET_ACCESS_KEY` | 3에서 만든 비밀 키 |
| `SES_REGION` | `ap-northeast-2` |
| `SES_FROM` | 인증한 도메인의 발신 주소. 예: `정부 혜택 도우미 <help@mail.govbenefit.kr>` |

- `MAIL_SENDER`가 비어 있으면 발송 구현이 없어 기동에 실패한다. `ses`인데 나머지 네 값 중 하나라도 비어 있어도 빠진 변수 이름을 남기고 기동에 실패한다.
- 앱은 이 값만 쓰고 `AWS_ACCESS_KEY_ID` 같은 SDK 기본 환경 변수나 `~/.aws` 설정은 읽지 않는다.
- `local` 프로필은 `application-local.yaml`의 `auth.mail.sender: logging`이 `.env` 값보다 우선한다. 로컬에서 실제 발송을 확인할 때는 환경 변수 `AUTH_MAIL_SENDER=ses`로 덮는다.
- `software.amazon.awssdk` 로그 수준을 DEBUG로 올리지 않는다. 요청 내용이 로그에 남을 수 있다.

## 7. 수신 확인

로컬에서 확인하는 예다. `backend/.env`에 `SES_*` 값을 넣고 `backend/`에서 실행한다.

```sh
SPRING_PROFILES_ACTIVE=local AUTH_MAIL_SENDER=ses ./gradlew bootRun
curl -s -i -X POST localhost:8080/api/auth/email-verification -H 'Content-Type: application/json' \
  -d '{"email":"<받을 주소>","ageConfirmed":true}'
```

1. 응답이 204인지 본다. 발송은 응답 뒤에 따로 일어나므로, 발송에 실패해도 204다.
2. 받은편지함(스팸함 포함)에서 `[정부 혜택 도우미] 가입 인증번호` 메일과 발신 주소를 확인한다.
3. 메일의 원본 보기(Gmail은 **원본 보기**)에서 `DKIM: 'PASS'`(도메인 `mail.govbenefit.kr`)와 `DMARC: 'PASS'`를 확인한다. SPF는 `amazonses.com` 기준으로 통과하므로 도메인이 발신 도메인과 다르게 보이는 것이 정상이다.
4. 메일의 번호로 확인 API가 성공하는지 본다.
   ```sh
   curl -s -X POST localhost:8080/api/auth/email-verification/confirm -H 'Content-Type: application/json' \
     -d '{"email":"<받을 주소>","code":"<번호>"}'
   ```
5. 메일이 오지 않으면 앱 로그에서 `가입 인증번호 메일을 보내지 못했습니다`와 그 뒤의 메시지를 찾는다. 로그에는 행 ID와 상태만 있다.
   - `SES 응답 상태 400 MessageRejected`: 샌드박스에서 인증하지 않은 수신자에게 보냈거나, 발신 주소의 도메인이 이 리전에서 인증되지 않았다.
   - `SES 응답 상태 403 ...`: 접근 키가 틀렸거나 IAM 정책이 `ses:SendEmail`을 허용하지 않는다(리전·도메인 ARN 확인).
   - `SES 응답 상태 400 SendingPausedException`, `AccountSuspendedException`: 계정 발송이 멈췄다. **Account dashboard**와 AWS에서 온 메일을 확인한다.
   - `SES 응답 상태 429 TooManyRequestsException`: 초당 속도나 하루 할당량을 넘었다. 다시 보내지 않으므로 이용자가 발송 간격 뒤 다시 요청해야 한다.
   - `SES 연결 실패: ...`: 서버에서 `email.ap-northeast-2.amazonaws.com`으로 나가는 연결을 확인한다.
   - 로그에 실패가 없는데 메일이 없으면 억제 목록(4)에 그 주소가 있는지 본다.

운영에서는 배포한 뒤 실제 주소로 같은 1\~3을 확인한다.

## 8. Resend 정리

2026-11-05까지는 Resend로 보냈다. 운영에서 SES로 실제 메일을 받은 것(7)을 확인한 뒤 정리한다. 그 전에는 이전 버전으로 되돌릴 수 있도록 남긴다.

1. Resend 대시보드의 **API Keys**에서 운영 키를 삭제한다.
2. **Domains**에서 `mail.govbenefit.kr`을 삭제한다.
3. DNS에서 Resend 레코드를 지운다: DKIM TXT(`resend._domainkey.mail.govbenefit.kr`), Return-Path 하위 도메인(`send.mail.govbenefit.kr`)의 MX와 SPF TXT. SES의 DKIM CNAME(`<토큰>._domainkey`)은 지우지 않는다.
4. 더 쓰지 않으면 Resend 계정을 삭제한다.
