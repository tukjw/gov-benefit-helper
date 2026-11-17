# 0049. 새 혜택 알림 발송의 종료 처리

- 상태: 채택
- 날짜: 2026-11-11
- 출처: #141, [플랜](../plans/141-notify-send-graceful-shutdown.md)

## 배경

[0048](0048-new-benefit-digest-send.md)은 종료할 때 실행 중인 발송을 Spring이 30초까지 기다린 뒤 interrupt한다고 적고, 그 안에 끝나지 않으면 SES 호출 중에 끊길 수 있다는 것을 한계로 두었다. 0048의 결정(상태 값, 재시도 규칙, 폴링 구조)은 그대로 두고, 이 한계가 실제로 어떻게 나타나는지에 맞춰 종료 처리를 새로 정한다.

#137 측정(`docs/research/notify-send-fault-baseline.md` 5.3절)에서 발송 중 배포 3번에 중복 접수가 3건 나왔다.

- 종료 신호를 받은 앱은 종료 대기 30초 동안 계속 보냈다.
- 대기가 끝난 뒤에도 발송은 멈추지 않았고, 그 상태에서 JPA와 커넥션 풀이 닫혔다. 그때 보내던 묶음은 SES가 접수했지만 `PENDING`으로 남아 새 앱이 다시 보냈다.
- 이전 앱의 발송 요약 로그도 남지 않았다.

1만 통을 보내는 실행 한 번이 약 26분이라, 발송 중에 배포하면 매번 이렇게 된다.

## 결정

- 발송 작업(`NewBenefitDigestSender`)은 자기 애플리케이션 컨텍스트의 `ContextClosedEvent`를 받으면 종료 중으로 표시한다. 한번 표시하면 되돌리지 않는다.
- 발송 루프는 묶음마다 준비 전에 이 표시를 확인하고, 종료 중이면 다음 묶음을 다루지 않고 실행을 끝낸다. 요약 로그는 `interrupted=true`로 남긴다.
- 이미 SES 호출에 들어간 묶음은 끊지 않고, 응답을 받아 결과(`ACCEPTED`, 재시도 대기, `FAILED`)를 기록한 뒤 멈춘다.
- 남은 묶음은 `PENDING`으로 남고, 다음에 뜬 앱의 첫 폴링이 보낸다(0048과 같다).
- 종료 대기 시간(Spring 종료 단계 30초, compose `stop_grace_period` 40초)은 바꾸지 않는다.

## 근거

- **interrupt가 아니라 종료 이벤트로 멈춘다**
  - 종료 순서(Spring Framework 7.0.9 `ExecutorConfigurationSupport`·`ExecutorLifecycleDelegate` 소스): `ContextClosedEvent`에서 스케줄러가 새 실행만 막는다. 생명주기 종료 단계에서 실행 중인 작업을 30초까지 기다리며, 작업에는 아무 신호도 보내지 않는다. 그 뒤 빈을 없앨 때 interrupt하는데, 이때 JPA와 커넥션 풀도 함께 닫혀 결과를 기록할 수 없다.
  - 발송 루프는 SES 호출 간격 대기(`Thread.sleep`)에서만 interrupt를 본다. #137에서 이 대기에 걸린 요청은 약 1%였다.
  - AWS SDK(2.57.9)는 응답을 받은 뒤에도 interrupt를 확인해 예외를 던진다(`AfterTransmissionExecutionInterceptorsStage`). 발송 구현은 이를 일시 실패로 바꾸므로, interrupt로 멈추면 SES가 접수한 메일을 실패로 기록해 다시 보낼 수 있다.
  - 종료 이벤트는 종료의 가장 앞 단계라, 스케줄러가 기다리기 시작하기 전에 신호가 닿는다. 루프가 반환하면 스케줄러의 대기가 바로 풀린다.
- **자기 컨텍스트만 본다**: 운영은 관리 포트(8081)를 따로 열어 자식 컨텍스트가 있고, 자식 컨텍스트의 이벤트도 부모의 리스너에 전달된다. 스케줄러(`ExecutorConfigurationSupport`)도 같은 방식으로 자기 컨텍스트만 본다.
- **보내던 메일은 끊지 않는다**: 한 건은 연결 5초 + 응답 10초(0047)와 기록 트랜잭션 안에 끝나 30초 대기보다 짧다. 끊으면 SES 접수 여부를 알 수 없어 중복이나 유실이 된다.
- 제외
  - `SmartLifecycle` 구현: 실행 상태를 따로 추적해야 하고, 스케줄러가 이미 실행 중인 작업을 기다리므로 얻는 것이 적다.
  - 스케줄러 종료 설정(`spring.task.scheduling.shutdown.await-termination`): 루프가 종료를 모르므로 남은 묶음을 다 보낼 때까지 계속 보내고, 40초 뒤 SIGKILL된다.
  - 한 실행이 보내는 양 제한: 짧게 잡아도 응답 유실이 섞이면 30초를 넘으므로 종료 확인이 따로 필요하다. 0048의 "대상이 빌 때까지 보낸다"를 바꾼다.
  - 발송 전용 스레드: 종료 확인은 똑같이 필요하고, 묶기와 같은 스레드라 동시에 돌지 않는다는 0048의 전제가 깨진다.
  - 점유 상태(`SENDING`): 종료 중 끊긴 묶음의 접수 여부를 알 수 없어 중복 대신 유실이 생긴다.
  - 배포 절차에서 발송 일시정지: 배포 스크립트를 거치지 않는 종료(컨테이너 재시작, VM 재부팅)는 그대로다.
- 확인: 테스트는 종료 이벤트를 받은 발송기가 보내던 묶음만 기록하고 멈추는지 본다. 실제 배포에서의 효과는 #141의 재측정(`docs/research/notify-send-graceful-shutdown.md`)에 있다.
- **한계**
  - 강제 종료(SIGKILL)와 응답 유실에서 생기는 중복은 그대로다. SES가 같은 요청을 한 번만 처리하게 하는 키를 받지 않기 때문이며 0048이 받아들인 한계다.
  - 보내던 한 건과 기록이 30초를 넘기면(DB가 멈춘 경우 등) 지금처럼 끊길 수 있다.

## 적용 위치

- `backend/src/main/java/kr/govbenefit/notification/service/NewBenefitDigestSender.java`: `onContextClosed`, `sendDue`
- `backend/src/test/java/kr/govbenefit/notification/service/NewBenefitDigestSendTest.java`
- `deploy/app/compose.yaml`: `stop_grace_period: 40s`(바꾸지 않음)

## 영향

- 발송 루프를 고치거나(발송 속도, 실행 단위) 발송 스레드를 늘릴 때도 묶음마다 종료 표시를 확인하고, 보내던 메일은 결과를 기록할 때까지 끊지 않는다.
- 종료 대기 시간이나 SES 응답 제한을 늘려 한 건의 최대 시간이 Spring 종료 대기(30초)에 가까워지면 이 결정을 다시 본다.
- 묶기·수집 등 다른 예약 작업은 이 결정의 대상이 아니다. 같은 문제가 확인되면 따로 정한다.
