package kr.govbenefit.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import kr.govbenefit.FixedClockConfiguration;
import kr.govbenefit.MutableClock;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.domain.UserAccount;
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
import kr.govbenefit.notification.domain.NewBenefitTarget;
import kr.govbenefit.notification.repository.NewBenefitDigestRepository;
import kr.govbenefit.notification.repository.NewBenefitTargetRepository;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 새 혜택 묶기(REQ-NOTIFY-03, 결정 0046). 알림 항목은 대상 선정을 거치지 않고 직접 넣는다.
 * 묶기는 {@link NewBenefitDigestBundler#bundleAll()}을 직접 부르고, 묶는 날짜는 시계를 그날 08:00으로 옮겨 정한다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class NewBenefitDigestTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate NOV_3 = LocalDate.of(2026, 11, 3);
    private static final LocalDate NOV_4 = LocalDate.of(2026, 11, 4);
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final LocalDate NOV_6 = LocalDate.of(2026, 11, 6);

    @Autowired
    private NewBenefitDigestBundler bundler;
    @Autowired
    private NewBenefitDigestWriter writer;
    @Autowired
    private NewBenefitDigestRepository digestRepository;
    @Autowired
    private NewBenefitTargetRepository targetRepository;
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

    private Long x;
    private Long y;
    private Long z;

    @BeforeEach
    void setUp() {
        moveTo(NOV_3);
        LocalDateTime now = LocalDateTime.now(clock);
        Long runId = collectionRunRepository.save(CollectionRun.start(now)).getId();
        x = createNewBenefit("DIGEST-X", runId, now);
        y = createNewBenefit("DIGEST-Y", runId, now);
        z = createNewBenefit("DIGEST-Z", runId, now);
    }

    @Test
    void 알림을_켠_사용자마다_묶지_않은_항목을_모두_그날_묶음_하나에_넣는다() {
        Long u1 = createUser("u1@example.com", true);
        Long u2 = createUser("u2@example.com", true);
        createUser("u3@example.com", true);
        addTarget(u1, x);
        addTarget(u1, y);
        addTarget(u2, x);

        bundleOn(NOV_5);

        assertThat(digests()).containsExactlyInAnyOrder(
                tuple(u1, NOV_5, NewBenefitDigestStatus.PENDING),
                tuple(u2, NOV_5, NewBenefitDigestStatus.PENDING));
        assertThat(items(u1, NOV_5)).containsExactlyInAnyOrder(x, y);
        assertThat(items(u2, NOV_5)).containsExactly(x);
        assertThat(unbundled()).isEmpty();
    }

    @Test
    void 묶기가_돌지_않은_날의_항목은_다음_묶음에_함께_들고_이미_묶은_항목은_다시_들지_않는다() {
        Long u1 = createUser("u1@example.com", true);
        addTarget(u1, x);
        bundleOn(NOV_3);
        // 11-04에 받은 항목은 그날 묶기가 돌지 않아 남는다.
        addTarget(u1, y);
        addTarget(u1, z);

        bundleOn(NOV_5);

        assertThat(digests()).containsExactlyInAnyOrder(
                tuple(u1, NOV_3, NewBenefitDigestStatus.PENDING),
                tuple(u1, NOV_5, NewBenefitDigestStatus.PENDING));
        assertThat(items(u1, NOV_3)).containsExactly(x);
        assertThat(items(u1, NOV_5)).containsExactlyInAnyOrder(y, z);
    }

    @Test
    void 같은_날_다시_묶어도_그날_묶음은_하나이고_그_뒤에_생긴_항목은_다음_날_묶음에_든다() {
        Long u1 = createUser("u1@example.com", true);
        addTarget(u1, x);
        bundleOn(NOV_5);
        addTarget(u1, y);

        bundleOn(NOV_5);

        assertThat(digests()).containsExactly(tuple(u1, NOV_5, NewBenefitDigestStatus.PENDING));
        assertThat(items(u1, NOV_5)).containsExactly(x);
        assertThat(unbundled()).containsExactly(tuple(u1, y));

        bundleOn(NOV_6);

        assertThat(items(u1, NOV_5)).containsExactly(x);
        assertThat(items(u1, NOV_6)).containsExactly(y);
        assertThat(unbundled()).isEmpty();
    }

    @Test
    void 알림을_끈_사용자의_묶지_않은_항목은_지우고_다시_켜도_묶지_않는다() {
        Long on = createUser("on@example.com", true);
        Long off = createUser("off@example.com", true);
        addTarget(off, x);
        bundleOn(NOV_4);
        changeNewBenefitNotify(off, false);
        addTarget(on, y);
        addTarget(off, y);

        bundleOn(NOV_5);

        assertThat(digests()).containsExactlyInAnyOrder(
                tuple(off, NOV_4, NewBenefitDigestStatus.PENDING),
                tuple(on, NOV_5, NewBenefitDigestStatus.PENDING));
        // 이미 묶은 항목은 지우지 않는다. 발송 직전의 설정 확인은 발송 쪽이 한다.
        assertThat(items(off, NOV_4)).containsExactly(x);
        assertThat(items(on, NOV_5)).containsExactly(y);
        assertThat(unbundled()).isEmpty();

        changeNewBenefitNotify(off, true);
        bundleOn(NOV_6);

        assertThat(digests()).hasSize(2);
    }

    @Test
    void 묶기가_도중에_멈춘_뒤_다시_묶으면_남은_사용자만_묶는다() {
        Long u1 = createUser("u1@example.com", true);
        Long u2 = createUser("u2@example.com", true);
        addTarget(u1, x);
        addTarget(u1, y);
        addTarget(u2, x);
        // u1까지 묶고 u2를 묶기 전에 멈춘 상태를 만든다. 사용자마다 묶음과 항목 연결을 한 트랜잭션으로 저장한다.
        moveTo(NOV_5);
        writer.bundle(u1, NOV_5, LocalDateTime.now(clock));
        Long digestOfU1 = digestId(u1, NOV_5);

        bundler.bundleAll();

        assertThat(digests()).containsExactlyInAnyOrder(
                tuple(u1, NOV_5, NewBenefitDigestStatus.PENDING),
                tuple(u2, NOV_5, NewBenefitDigestStatus.PENDING));
        assertThat(digestId(u1, NOV_5)).isEqualTo(digestOfU1);
        assertThat(items(u1, NOV_5)).containsExactlyInAnyOrder(x, y);
        assertThat(items(u2, NOV_5)).containsExactly(x);
        assertThat(unbundled()).isEmpty();
    }

    private void moveTo(LocalDate date) {
        clock.set(date.atTime(LocalTime.of(8, 0)).atZone(ZONE).toInstant());
    }

    private void bundleOn(LocalDate date) {
        moveTo(date);
        bundler.bundleAll();
    }

    private Long createNewBenefit(String serviceId, Long runId, LocalDateTime now) {
        Long benefitId = benefitRepository.save(Benefit.create(serviceId, new BenefitContent(serviceId, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null),
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

    /** 대상 선정이 저장한 것처럼 묶지 않은 알림 항목을 넣는다. 저장소의 insert는 트랜잭션 안에서만 돈다. */
    private void addTarget(Long userId, Long benefitId) {
        jdbcTemplate.update("INSERT INTO new_benefit_target (user_id, benefit_id) VALUES (?, ?)", userId, benefitId);
    }

    private List<Tuple> digests() {
        return digestRepository.findAll().stream()
                .map(digest -> tuple(digest.getUserId(), digest.getDigestDate(), digest.getStatus()))
                .toList();
    }

    private Long digestId(Long userId, LocalDate digestDate) {
        return digestRepository.findAll().stream()
                .filter(digest -> digest.getUserId().equals(userId) && digest.getDigestDate().equals(digestDate))
                .map(NewBenefitDigest::getId)
                .findFirst().orElseThrow();
    }

    /** 사용자의 그날 묶음에 든 혜택 ID */
    private List<Long> items(Long userId, LocalDate digestDate) {
        Long digestId = digestId(userId, digestDate);
        return targetRepository.findAll().stream()
                .filter(target -> digestId.equals(target.getDigestId()))
                .map(NewBenefitTarget::getBenefitId)
                .toList();
    }

    private List<Tuple> unbundled() {
        return targetRepository.findAll().stream()
                .filter(target -> target.getDigestId() == null)
                .map(target -> tuple(target.getUserId(), target.getBenefitId()))
                .toList();
    }
}
