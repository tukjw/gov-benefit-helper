package kr.govbenefit.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import kr.govbenefit.FixedClockConfiguration;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.mail.SesStubServer;
import kr.govbenefit.account.mail.SesStubServer.ReceivedRequest;
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
import kr.govbenefit.notification.domain.NewBenefitDigest;
import kr.govbenefit.notification.domain.NewBenefitDigestStatus;
import kr.govbenefit.notification.repository.NewBenefitDigestRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code auth.mail.sender=ses}로 띄우면 새 혜택 알림이 Amazon SES API로 요청된다(결정 0047, 0048). 실제 SES 대신 로컬 stub
 * 서버가 받는다. SES 오류와 연결 끊김에 따라 다시 보내거나 최종 실패로 끝내고, 실패 로그에 수신 주소·본문·키가 남지 않는다.
 *
 * <p>이 클래스만 쓰는 설정이라 컨텍스트를 다른 테스트와 공유하지 않는다. 끝나면 바로 닫는다({@link DirtiesContext}).
 */
@SpringBootTest(properties = {"gov24.schedule.enabled=false", "auth.mail.sender=ses",
        "auth.mail.ses.access-key-id=" + NewBenefitDigestSesTest.ACCESS_KEY_ID,
        "auth.mail.ses.secret-access-key=" + NewBenefitDigestSesTest.SECRET_ACCESS_KEY,
        "auth.mail.ses.region=ap-northeast-2", "auth.mail.ses.from=" + NewBenefitDigestSesTest.FROM})
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class NewBenefitDigestSesTest {

    static final String ACCESS_KEY_ID = "AKIATESTNOTIFY8B4C";
    static final String SECRET_ACCESS_KEY = "test/Notify+Secret8b4c1e";
    static final String FROM = "정부 혜택 도우미 <no-reply@govbenefit.kr>";
    private static final String EMAIL = "alice@example.com";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);

    private static final SesStubServer stub = SesStubServer.start();

    @DynamicPropertySource
    static void sesProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.mail.ses.endpoint", stub::endpoint);
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @Autowired
    private NewBenefitDigestSender sender;
    @Autowired
    private NewBenefitDigestBundler bundler;
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

    @BeforeEach
    void setUp() {
        stub.reset();
        clock.set(NOV_5.atTime(8, 0).atZone(ZONE).toInstant());
        LocalDateTime now = LocalDateTime.now(clock);
        Long runId = collectionRunRepository.save(CollectionRun.start(now)).getId();
        Long x = createNewBenefit("SES-X", runId, now);
        Long y = createNewBenefit("SES-Y", runId, now);
        UserAccount account = UserAccount.create(EMAIL, "{noop}x", now);
        account.changeNewBenefitNotify(true);
        Long userId = userAccountRepository.save(account).getId();
        for (Long benefitId : new Long[] {x, y}) {
            jdbcTemplate.update("INSERT INTO new_benefit_target (user_id, benefit_id) VALUES (?, ?)", userId,
                    benefitId);
        }
        bundler.bundleAll();
    }

    @Test
    void 묶음이_SES로_한_번_요청되고_새_혜택과_안내가_모두_담긴다() {
        sender.sendDue();

        assertThat(stub.received()).hasSize(1);
        ReceivedRequest request = stub.last();
        assertThat(request.path()).isEqualTo(SesStubServer.SEND_EMAIL_PATH);
        assertThat(request.from()).isEqualTo(FROM);
        assertThat(request.toAddresses()).containsExactly(EMAIL);
        assertThat(request.subject()).isEqualTo("[정부 혜택 도우미] 조건에 맞는 새 혜택 2건");
        assertThat(request.text()).contains(
                "1. SES-X 혜택\n   운영 기관: SES-X 기관\n   https://govbenefit.kr/benefits/SES-X\n",
                "2. SES-Y 혜택\n   운영 기관: SES-Y 기관\n   https://govbenefit.kr/benefits/SES-Y\n",
                "'해당'은 회원님이 입력한 프로필을 혜택의 대상 조건과 비교한 결과입니다.",
                "신청하기 전에 혜택 상세 화면의 원문을 꼭 확인하세요.",
                "알림을 끄려면 https://govbenefit.kr 에 로그인한 뒤 알림 설정에서 끄세요.");
        assertThat(digest().getStatus()).isEqualTo(NewBenefitDigestStatus.ACCEPTED);
    }

    @Test
    void SES가_속도_초과로_응답하면_1분_뒤에_다시_보내_접수된다() {
        stub.respondWithError(429, "TooManyRequestsException", "Maximum sending rate exceeded. " + EMAIL);

        sender.sendDue();

        assertThat(digest().getStatus()).isEqualTo(NewBenefitDigestStatus.PENDING);
        assertThat(digest().getLastError()).isEqualTo("SES 응답 상태 429 TooManyRequestsException");

        stub.reset();
        clock.advance(Duration.ofMinutes(1));
        sender.sendDue();

        assertThat(stub.received()).hasSize(1);
        assertThat(digest().getStatus()).isEqualTo(NewBenefitDigestStatus.ACCEPTED);
        assertThat(digest().getAttemptCount()).isEqualTo(2);
    }

    @Test
    void SES와_연결이_끊기면_1분_뒤에_다시_보내_접수된다() {
        stub.disconnect();

        sender.sendDue();

        assertThat(digest().getStatus()).isEqualTo(NewBenefitDigestStatus.PENDING);
        assertThat(digest().getLastError()).startsWith("SES 연결 실패: ");

        stub.reset();
        clock.advance(Duration.ofMinutes(1));
        sender.sendDue();

        assertThat(stub.received()).hasSize(1);
        assertThat(digest().getStatus()).isEqualTo(NewBenefitDigestStatus.ACCEPTED);
    }

    @Test
    void SES가_요청을_거절하면_다시_보내지_않고_실패_로그에_수신_주소_본문_키가_없다(CapturedOutput output) {
        // 샌드박스에서 인증하지 않은 수신자로 보내면 설명에 수신 주소가 들어간다.
        stub.respondWithError(400, "MessageRejected",
                "Email address is not verified. The following identities failed the check: " + EMAIL);

        sender.sendDue();
        clock.advance(Duration.ofHours(1));
        sender.sendDue();

        assertThat(stub.received()).hasSize(1);
        NewBenefitDigest digest = digest();
        assertThat(digest.getStatus()).isEqualTo(NewBenefitDigestStatus.FAILED);
        assertThat(digest.getAttemptCount()).isEqualTo(1);
        assertThat(output).contains("새 혜택 알림 발송 실패 digestId=" + digest.getId(),
                "SES 응답 상태 400 MessageRejected");
        assertThat(output).doesNotContain(EMAIL, "SES-X 혜택", "SES-Y 혜택", ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    }

    private NewBenefitDigest digest() {
        return digestRepository.findAll().getFirst();
    }

    private Long createNewBenefit(String serviceId, Long runId, LocalDateTime now) {
        Long benefitId = benefitRepository.save(Benefit.create(serviceId, new BenefitContent(serviceId + " 혜택", null,
                null, null, null, null, null, null, null, null, null, null, serviceId + " 기관", null, null, null, null),
                new BenefitNormalization(null, null, ApplyPeriodRule.ALWAYS), now)).getId();
        newBenefitRepository.save(NewBenefit.detect(benefitId, runId, now));
        return benefitId;
    }
}
