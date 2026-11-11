package kr.govbenefit.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import kr.govbenefit.FixedClockConfiguration;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.mail.AccountMailSendException;
import kr.govbenefit.account.mail.AccountMailSender;
import kr.govbenefit.account.mail.NewBenefitMailItem;
import kr.govbenefit.account.mail.RecordingAccountMailSender;
import kr.govbenefit.account.mail.RecordingAccountMailSender.SentDigest;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.benefit.domain.ApplyPeriodRule;
import kr.govbenefit.benefit.domain.Benefit;
import kr.govbenefit.benefit.domain.BenefitContent;
import kr.govbenefit.benefit.domain.BenefitNormalization;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.collection.domain.CollectionRun;
import kr.govbenefit.collection.domain.NewBenefit;
import kr.govbenefit.collection.repository.CollectionRunRepository;
import kr.govbenefit.collection.repository.NewBenefitRepository;
import kr.govbenefit.notification.config.NewBenefitSendProperties;
import kr.govbenefit.notification.domain.NewBenefitDigest;
import kr.govbenefit.notification.domain.NewBenefitDigestStatus;
import kr.govbenefit.notification.repository.NewBenefitDigestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 새 혜택 알림 발송(REQ-NOTIFY-02, 결정 0048). 메일은 가짜 발송({@link RecordingAccountMailSender})에 기록하고, 실패는 가짜
 * 발송이 던지는 예외로 만든다. 묶음은 시계를 그날 08:00으로 옮겨 {@link NewBenefitDigestBundler#bundleAll()}로 만들고, 발송은
 * {@link NewBenefitDigestSender#sendDue()}를 직접 부른다.
 *
 * <p>앱 종료(결정 0049)는 공유 컨텍스트를 닫지 않고, 테스트에서 따로 만든 발송기에 종료 이벤트를 넘겨 재현한다. 공유 컨텍스트에
 * 종료 이벤트를 발행하면 스케줄러가 실제로 종료돼 다른 테스트가 깨진다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class NewBenefitDigestSendTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate NOV_4 = LocalDate.of(2026, 11, 4);
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final String SITE_URL = "https://govbenefit.kr";

    @Autowired
    private NewBenefitDigestSender sender;
    @Autowired
    private NewBenefitDigestSendWriter writer;
    @Autowired
    private NewBenefitDigestBundler bundler;
    @Autowired
    private RecordingAccountMailSender mail;
    @Autowired
    private NewBenefitSendProperties properties;
    @Autowired
    private NewBenefitDigestRepository digestRepository;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private BenefitRepository benefitRepository;
    @Autowired
    private CollectionRunRepository collectionRunRepository;
    @Autowired
    private NewBenefitRepository newBenefitRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ApplicationContext applicationContext;

    private Long x;
    private Long y;

    @BeforeEach
    void setUp() {
        mail.clear();
        moveTo(NOV_4, LocalTime.of(8, 0));
        LocalDateTime now = LocalDateTime.now(clock);
        Long runId = collectionRunRepository.save(CollectionRun.start(now)).getId();
        x = createNewBenefit("SEND-X", runId, now);
        y = createNewBenefit("SEND-Y", runId, now);
    }

    @Test
    void 발송_대기_묶음마다_메일_한_통에_묶음의_새_혜택을_모두_담아_보낸다() {
        Long u1 = createUser("u1@example.com", true);
        Long u2 = createUser("u2@example.com", true);
        addTarget(u1, x);
        addTarget(u1, y);
        addTarget(u2, x);
        bundleOn(NOV_5);

        sender.sendDue();

        assertThat(mail.sentDigests()).hasSize(2);
        assertThat(sentTo("u1@example.com").benefits()).containsExactly(item("SEND-X"), item("SEND-Y"));
        assertThat(sentTo("u2@example.com").benefits()).containsExactly(item("SEND-X"));
        assertThat(mail.sentDigests()).allSatisfy(sent -> assertThat(sent.siteUrl()).isEqualTo(SITE_URL));
        for (Long user : List.of(u1, u2)) {
            NewBenefitDigest digest = digest(user, NOV_5);
            assertThat(digest.getStatus()).isEqualTo(NewBenefitDigestStatus.ACCEPTED);
            assertThat(digest.getAttemptCount()).isEqualTo(1);
            assertThat(digest.getAcceptedAt()).isEqualTo(NOV_5.atTime(8, 0));
        }

        clock.advance(Duration.ofMinutes(1));
        sender.sendDue();

        assertThat(mail.sentDigests()).hasSize(2);
    }

    @Test
    void 보내기_직전에_알림이_꺼진_사용자의_묶음은_보내지_않고_다시_켜도_보내지_않는다() {
        Long off = createUser("off@example.com", true);
        Long on = createUser("on@example.com", true);
        addTarget(off, x);
        addTarget(on, x);
        bundleOn(NOV_5);
        changeNewBenefitNotify(off, false);

        sender.sendDue();

        assertThat(mail.sentDigests()).extracting(SentDigest::email).containsExactly("on@example.com");
        assertThat(digest(off, NOV_5).getStatus()).isEqualTo(NewBenefitDigestStatus.CANCELED);
        assertThat(digest(off, NOV_5).getAttemptCount()).isZero();
        assertThat(digest(on, NOV_5).getStatus()).isEqualTo(NewBenefitDigestStatus.ACCEPTED);

        changeNewBenefitNotify(off, true);
        clock.advance(Duration.ofMinutes(1));
        sender.sendDue();

        assertThat(mail.sentDigests()).extracting(SentDigest::email).containsExactly("on@example.com");
        assertThat(digest(off, NOV_5).getStatus()).isEqualTo(NewBenefitDigestStatus.CANCELED);
    }

    @Test
    void 정한_초당_건수를_넘겨_보내지_않는다() {
        int rate = properties.ratePerSecond();
        int users = rate * 2 + 1;
        for (int i = 0; i < users; i++) {
            addTarget(createUser("rate" + i + "@example.com", true), x);
        }
        bundleOn(NOV_5);

        sender.sendDue();

        List<Long> sentAt = mail.sentDigests().stream().map(SentDigest::sentAtNanos).sorted().toList();
        assertThat(sentAt).hasSize(users);
        // 연속한 (초당 건수 + 1)건이 1초 안에 들지 않으면 어느 1초에도 초당 건수를 넘지 않는다. 기록은 대기가 끝난 직후라
        // GC 정지 같은 지연만큼 늦을 수 있어 10ms를 허용한다. 발송 간격(초당 10건이면 100ms)보다 훨씬 작다.
        long minimumSpan = Duration.ofSeconds(1).minusMillis(10).toNanos();
        for (int i = 0; i + rate < sentAt.size(); i++) {
            assertThat(sentAt.get(i + rate) - sentAt.get(i)).isGreaterThanOrEqualTo(minimumSpan);
        }
    }

    @Test
    void 일시_실패한_묶음은_정한_간격_뒤에_다시_보내_성공하면_접수로_끝난다() {
        Long u1 = createUser("u1@example.com", true);
        addTarget(u1, x);
        bundleOn(NOV_5);
        mail.failNextDigests(retryable(), retryable());

        sender.sendDue();

        NewBenefitDigest first = digest(u1, NOV_5);
        assertThat(first.getStatus()).isEqualTo(NewBenefitDigestStatus.PENDING);
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getNextAttemptAt()).isEqualTo(NOV_5.atTime(8, 1));
        assertThat(first.getLastError()).isEqualTo("SES 응답 상태 429 TooManyRequestsException");

        // 다음 시도 시각 전에는 보내지 않는다.
        clock.advance(Duration.ofSeconds(30));
        sender.sendDue();

        assertThat(digest(u1, NOV_5).getAttemptCount()).isEqualTo(1);

        moveTo(NOV_5, LocalTime.of(8, 1));
        sender.sendDue();

        assertThat(digest(u1, NOV_5).getAttemptCount()).isEqualTo(2);
        assertThat(digest(u1, NOV_5).getNextAttemptAt()).isEqualTo(NOV_5.atTime(8, 6));

        moveTo(NOV_5, LocalTime.of(8, 6));
        sender.sendDue();

        NewBenefitDigest accepted = digest(u1, NOV_5);
        assertThat(accepted.getStatus()).isEqualTo(NewBenefitDigestStatus.ACCEPTED);
        assertThat(accepted.getAttemptCount()).isEqualTo(3);
        assertThat(accepted.getAcceptedAt()).isEqualTo(NOV_5.atTime(8, 6));
        assertThat(mail.sentDigests()).hasSize(1);
    }

    @Test
    void 일시_실패가_재시도_횟수를_넘기면_최종_실패로_끝나고_더_보내지_않는다() {
        Long u1 = createUser("u1@example.com", true);
        addTarget(u1, x);
        bundleOn(NOV_5);
        mail.failNextDigests(retryable(), retryable(), retryable(), retryable(), retryable(), retryable());
        // 첫 시도와 재시도 다섯 번(1분, 5분, 30분, 2시간, 6시간 뒤)
        List<LocalTime> attempts = List.of(LocalTime.of(8, 0), LocalTime.of(8, 1), LocalTime.of(8, 6),
                LocalTime.of(8, 36), LocalTime.of(10, 36), LocalTime.of(16, 36));

        for (int i = 0; i < attempts.size(); i++) {
            moveTo(NOV_5, attempts.get(i));
            sender.sendDue();

            NewBenefitDigest digest = digest(u1, NOV_5);
            assertThat(digest.getAttemptCount()).isEqualTo(i + 1);
            if (i + 1 < attempts.size()) {
                assertThat(digest.getStatus()).isEqualTo(NewBenefitDigestStatus.PENDING);
                assertThat(digest.getNextAttemptAt()).isEqualTo(NOV_5.atTime(attempts.get(i + 1)));
            }
        }

        assertThat(digest(u1, NOV_5).getStatus()).isEqualTo(NewBenefitDigestStatus.FAILED);

        moveTo(NOV_5, LocalTime.of(23, 0));
        sender.sendDue();

        assertThat(digest(u1, NOV_5).getAttemptCount()).isEqualTo(6);
        assertThat(mail.sentDigests()).isEmpty();
    }

    @Test
    void 다음_시도_시각이_묶은_날_24시를_넘으면_최종_실패로_끝난다() {
        Long u1 = createUser("u1@example.com", true);
        addTarget(u1, x);
        bundleOn(NOV_5);
        mail.failNextDigests(retryable(), retryable());

        moveTo(NOV_5, LocalTime.of(23, 58));
        sender.sendDue();

        assertThat(digest(u1, NOV_5).getStatus()).isEqualTo(NewBenefitDigestStatus.PENDING);
        assertThat(digest(u1, NOV_5).getNextAttemptAt()).isEqualTo(NOV_5.atTime(23, 59));

        // 두 번째 실패 뒤의 다음 시도(5분 뒤, 00:04)는 묶은 날 24:00이 지난다.
        moveTo(NOV_5, LocalTime.of(23, 59));
        sender.sendDue();

        assertThat(digest(u1, NOV_5).getStatus()).isEqualTo(NewBenefitDigestStatus.FAILED);
        assertThat(digest(u1, NOV_5).getAttemptCount()).isEqualTo(2);
        assertThat(mail.sentDigests()).isEmpty();
    }

    @Test
    void 다시_보내도_성공할_수_없는_실패는_재시도_없이_최종_실패로_끝난다() {
        Long u1 = createUser("u1@example.com", true);
        addTarget(u1, x);
        bundleOn(NOV_5);
        mail.failNextDigests(new AccountMailSendException("SES 응답 상태 400 MessageRejected", false));

        sender.sendDue();

        NewBenefitDigest digest = digest(u1, NOV_5);
        assertThat(digest.getStatus()).isEqualTo(NewBenefitDigestStatus.FAILED);
        assertThat(digest.getAttemptCount()).isEqualTo(1);
        assertThat(digest.getLastError()).isEqualTo("SES 응답 상태 400 MessageRejected");

        clock.advance(Duration.ofHours(1));
        sender.sendDue();

        assertThat(digest(u1, NOV_5).getAttemptCount()).isEqualTo(1);
        assertThat(mail.sentDigests()).isEmpty();
    }

    @Test
    void 발송이_도중에_멈춘_뒤_다시_보내면_보내지_않은_묶음만_보낸다() {
        Long u1 = createUser("u1@example.com", true);
        addTarget(u1, x);
        bundleOn(NOV_5);
        sender.sendDue();
        Long u2 = createUser("u2@example.com", true);
        Long u3 = createUser("u3@example.com", true);
        addTarget(u2, x);
        addTarget(u3, x);
        bundleOn(NOV_5);
        mail.clear();
        // u2를 보내는 중에 프로세스가 끝난 것처럼, 발송기가 아닌 곳의 예외로 실행을 멈춘다.
        mail.failNextDigests(new IllegalStateException("발송 중 종료"));

        assertThatThrownBy(sender::sendDue).isInstanceOf(IllegalStateException.class);

        for (Long user : List.of(u2, u3)) {
            assertThat(digest(user, NOV_5).getStatus()).isEqualTo(NewBenefitDigestStatus.PENDING);
            assertThat(digest(user, NOV_5).getAttemptCount()).isZero();
        }

        sender.sendDue();

        assertThat(mail.sentDigests()).extracting(SentDigest::email)
                .containsExactly("u2@example.com", "u3@example.com");
        for (Long user : List.of(u1, u2, u3)) {
            assertThat(digest(user, NOV_5).getStatus()).isEqualTo(NewBenefitDigestStatus.ACCEPTED);
            assertThat(digest(user, NOV_5).getAttemptCount()).isEqualTo(1);
        }
    }

    @Test
    void 발송_중에_앱이_종료되면_보내던_묶음은_결과를_기록하고_다음_묶음은_보내지_않는다(CapturedOutput output) {
        Long u1 = createUser("u1@example.com", true);
        Long u2 = createUser("u2@example.com", true);
        addTarget(u1, x);
        addTarget(u2, x);
        bundleOn(NOV_5);

        senderClosedOnFirstSend(applicationContext).sendDue();

        assertThat(mail.sentDigests()).extracting(SentDigest::email).containsExactly("u1@example.com");
        NewBenefitDigest sent = digest(u1, NOV_5);
        assertThat(sent.getStatus()).isEqualTo(NewBenefitDigestStatus.ACCEPTED);
        assertThat(sent.getAttemptCount()).isEqualTo(1);
        assertThat(sent.getAcceptedAt()).isEqualTo(NOV_5.atTime(8, 0));
        NewBenefitDigest left = digest(u2, NOV_5);
        assertThat(left.getStatus()).isEqualTo(NewBenefitDigestStatus.PENDING);
        assertThat(left.getAttemptCount()).isZero();
        assertThat(output).contains("새 혜택 알림 발송 accepted=1 retrying=0 failed=0 skipped=0 interrupted=true");

        // 다시 뜬 앱의 첫 실행은 남은 묶음만 보낸다.
        clock.advance(Duration.ofSeconds(30));
        sender.sendDue();

        assertThat(mail.sentDigests()).extracting(SentDigest::email)
                .containsExactly("u1@example.com", "u2@example.com");
        assertThat(digest(u2, NOV_5).getStatus()).isEqualTo(NewBenefitDigestStatus.ACCEPTED);
        assertThat(digest(u1, NOV_5).getAttemptCount()).isEqualTo(1);
    }

    @Test
    void 다른_컨텍스트가_닫혀도_발송을_멈추지_않는다() {
        Long u1 = createUser("u1@example.com", true);
        Long u2 = createUser("u2@example.com", true);
        addTarget(u1, x);
        addTarget(u2, x);
        bundleOn(NOV_5);

        // 운영의 관리 포트처럼 자식 컨텍스트가 닫히는 경우
        senderClosedOnFirstSend(new GenericApplicationContext()).sendDue();

        assertThat(mail.sentDigests()).extracting(SentDigest::email)
                .containsExactly("u1@example.com", "u2@example.com");
        for (Long user : List.of(u1, u2)) {
            assertThat(digest(user, NOV_5).getStatus()).isEqualTo(NewBenefitDigestStatus.ACCEPTED);
        }
    }

    @Test
    void 묶은_날이_지난_발송_대기_묶음은_보내지_않고_최종_실패로_끝난다() {
        Long u1 = createUser("u1@example.com", true);
        addTarget(u1, x);
        bundleOn(NOV_4);

        moveTo(NOV_5, LocalTime.of(8, 0));
        sender.sendDue();

        NewBenefitDigest digest = digest(u1, NOV_4);
        assertThat(digest.getStatus()).isEqualTo(NewBenefitDigestStatus.FAILED);
        assertThat(digest.getAttemptCount()).isZero();
        assertThat(mail.sentDigests()).isEmpty();
    }

    /**
     * 이 앱의 컨텍스트를 쓰는 발송기를 따로 만든다. 첫 새 혜택 알림을 SES가 받은 직후, 결과를 기록하기 전에 {@code closed}의
     * 종료 이벤트를 받는다. 메일은 공유 가짜 발송({@link #mail})에 기록한다.
     */
    private NewBenefitDigestSender senderClosedOnFirstSend(ApplicationContext closed) {
        AtomicReference<NewBenefitDigestSender> closing = new AtomicReference<>();
        AccountMailSender closingMail = new AccountMailSender() {
            @Override
            public void sendSignupCode(String email, String code, Duration validFor) {
                mail.sendSignupCode(email, code, validFor);
            }

            @Override
            public void sendPasswordReset(String email, String resetUrl, Duration validFor) {
                mail.sendPasswordReset(email, resetUrl, validFor);
            }

            @Override
            public void sendNewBenefitDigest(String email, List<NewBenefitMailItem> benefits, String siteUrl) {
                mail.sendNewBenefitDigest(email, benefits, siteUrl);
                if (mail.sentDigests().size() == 1) {
                    closing.get().onContextClosed(new ContextClosedEvent(closed));
                }
            }
        };
        NewBenefitDigestSender closingSender = new NewBenefitDigestSender(digestRepository, writer, closingMail, clock,
                properties, applicationContext);
        closing.set(closingSender);
        return closingSender;
    }

    private static AccountMailSendException retryable() {
        return new AccountMailSendException("SES 응답 상태 429 TooManyRequestsException", true);
    }

    private static NewBenefitMailItem item(String serviceId) {
        return new NewBenefitMailItem(serviceId + " 혜택", serviceId + " 기관", SITE_URL + "/benefits/" + serviceId);
    }

    private void moveTo(LocalDate date, LocalTime time) {
        clock.set(date.atTime(time).atZone(ZONE).toInstant());
    }

    private void bundleOn(LocalDate date) {
        moveTo(date, LocalTime.of(8, 0));
        bundler.bundleAll();
    }

    /** 이름은 {@code <서비스ID> 혜택}, 운영 기관은 {@code <서비스ID> 기관}이다. */
    private Long createNewBenefit(String serviceId, Long runId, LocalDateTime now) {
        Long benefitId = benefitRepository.save(Benefit.create(serviceId, new BenefitContent(serviceId + " 혜택", null,
                null, null, null, null, null, null, null, null, null, null, serviceId + " 기관", null, null, null, null),
                new BenefitNormalization(null, null, ApplyPeriodRule.ALWAYS), now)).getId();
        newBenefitRepository.save(NewBenefit.detect(benefitId, runId, now));
        return benefitId;
    }

    private Long createUser(String email, boolean newBenefitNotify) {
        UserAccount account = UserAccount.create(email, "{noop}x", LocalDateTime.now(clock));
        account.changeNewBenefitNotify(newBenefitNotify);
        return userAccountRepository.save(account).getId();
    }

    private void changeNewBenefitNotify(Long userId, boolean enabled) {
        UserAccount account = userAccountRepository.findById(userId).orElseThrow();
        account.changeNewBenefitNotify(enabled);
        userAccountRepository.save(account);
    }

    /** 대상 선정이 저장한 것처럼 묶지 않은 알림 항목을 넣는다. */
    private void addTarget(Long userId, Long benefitId) {
        jdbcTemplate.update("INSERT INTO new_benefit_target (user_id, benefit_id) VALUES (?, ?)", userId, benefitId);
    }

    private NewBenefitDigest digest(Long userId, LocalDate digestDate) {
        return digestRepository.findAll().stream()
                .filter(digest -> digest.getUserId().equals(userId) && digest.getDigestDate().equals(digestDate))
                .findFirst().orElseThrow();
    }

    private SentDigest sentTo(String email) {
        return mail.sentDigests().stream().filter(sent -> sent.email().equals(email)).findFirst().orElseThrow();
    }
}
