package kr.govbenefit.notification.service;

import static kr.govbenefit.collection.domain.SourceEndpoint.SERVICE_LIST;
import static kr.govbenefit.collection.domain.SourceEndpoint.SUPPORT_CONDITIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.account.domain.UserAccount;
import kr.govbenefit.account.repository.UserAccountRepository;
import kr.govbenefit.benefit.repository.BenefitRepository;
import kr.govbenefit.collection.Gov24StubServer;
import kr.govbenefit.collection.domain.NewBenefit;
import kr.govbenefit.collection.domain.NewBenefitStatus;
import kr.govbenefit.collection.repository.NewBenefitRepository;
import kr.govbenefit.collection.service.CollectionService;
import kr.govbenefit.match.domain.MatchResult;
import kr.govbenefit.match.service.MatchService;
import kr.govbenefit.notification.domain.NewBenefitTarget;
import kr.govbenefit.notification.repository.NewBenefitTargetRepository;
import kr.govbenefit.profile.domain.Gender;
import kr.govbenefit.profile.domain.HouseholdAttribute;
import kr.govbenefit.profile.domain.LifeAttribute;
import kr.govbenefit.profile.domain.ProfileValues;
import kr.govbenefit.profile.domain.UserProfile;
import kr.govbenefit.profile.repository.UserProfileRepository;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 수집으로 새 혜택을 만들고 대상 선정을 검증한다(REQ-NOTIFY-03). 수집 설정은 {@code CollectionServiceTest}와 같다.
 * 새 혜택은 C(체육시설 이용요금 감면(경로))다. 개인 대상이고 65세 이상이며, 운영 기관이 서울 용산구다.
 * 기준선 수집에서 C를 빼고, 사용자를 만든 뒤 C를 되살려 수집한다.
 */
@SpringBootTest(properties = {
        "gov24.schedule.enabled=false",
        "gov24.retry-delay=10ms"
})
@Import(TestcontainersConfiguration.class)
class NewBenefitTargetSelectionTest {

    private static final String SERVICE_KEY = "stub-service-key-6d1f0c";
    private static final String B = "403000000173";
    private static final String C = "O00024000031";
    private static final String AUTH_FAILURE = "{\"code\":-4,\"msg\":\"등록되지 않은 인증키 입니다.\"}";
    private static final String SEOUL = "6110000";
    private static final String YONGSAN = "3020000";
    private static final String GANGNAM = "3220000";

    private static final Gov24StubServer stub = Gov24StubServer.start();

    @DynamicPropertySource
    static void gov24Properties(DynamicPropertyRegistry registry) {
        registry.add("gov24.base-url", stub::baseUrl);
        registry.add("gov24.service-key", () -> SERVICE_KEY);
        registry.add("gov24.per-page", () -> 2);
    }

    @AfterAll
    static void closeStub() {
        stub.close();
    }

    @Autowired
    private CollectionService collectionService;
    @Autowired
    private NewBenefitTargetSelector selector;
    @Autowired
    private MatchService matchService;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private UserProfileRepository userProfileRepository;
    @Autowired
    private BenefitRepository benefitRepository;
    @Autowired
    private NewBenefitRepository newBenefitRepository;
    @Autowired
    private NewBenefitTargetRepository targetRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        stub.reset();
    }

    @Test
    void 알림을_켠_사용자_중_조건_비교_결과가_해당인_사용자만_대상으로_고른다() {
        Users users = collectBaselineAndCreateUsers(C);
        stub.reset();

        collectionService.collectAll();

        Long c = benefitId(C);
        assertThat(targets()).containsExactly(tuple(users.match(), c));
        assertThat(newBenefit(c).getTargetsSelectedAt()).isNotNull();
    }

    @Test
    void 대상은_선정_시점의_조건_비교_결과와_같다() {
        Users users = collectBaselineAndCreateUsers(C);
        stub.reset();

        collectionService.collectAll();

        // 픽스처가 의도한 경우(해당·확인 필요·알림 끔)를 모두 다루는지 먼저 확인한다.
        assertThat(result(users.match())).isEqualTo(MatchResult.MATCH);
        assertThat(result(users.otherRegion())).isEqualTo(MatchResult.NEEDS_CHECK);
        assertThat(result(users.noProfile())).isEqualTo(MatchResult.NEEDS_CHECK);
        assertThat(result(users.notifyOff())).isEqualTo(MatchResult.MATCH);
        Long c = benefitId(C);
        List<Tuple> expected = Stream.of(users.match(), users.otherRegion(), users.noProfile())
                .filter(userId -> result(userId) == MatchResult.MATCH)
                .map(userId -> tuple(userId, c))
                .toList();
        assertThat(targets()).containsExactlyElementsOf(expected);
    }

    @Test
    void 개인_대상이_아닌_새_혜택은_대상이_없어도_선정을_마친다() {
        Users users = collectBaselineAndCreateUsers(C);
        stub.reset();
        stub.record(SERVICE_LIST, C).put("사용자구분", "법인");

        collectionService.collectAll();

        assertThat(result(users.match())).isEqualTo(MatchResult.MISMATCH);
        assertThat(targets()).isEmpty();
        assertThat(newBenefit(benefitId(C)).getTargetsSelectedAt()).isNotNull();
    }

    @Test
    void 지원조건을_받지_못한_새_혜택은_고르지_않고_판단_가능이_된_뒤에_고른다() {
        Users users = collectBaselineAndCreateUsers(C);
        stub.reset();
        stub.override(SUPPORT_CONDITIONS, 2, 401, AUTH_FAILURE);
        collectionService.collectAll();
        Long c = benefitId(C);
        assertThat(newBenefit(c).getStatus()).isEqualTo(NewBenefitStatus.WAITING_CONDITIONS);
        assertThat(newBenefit(c).getTargetsSelectedAt()).isNull();
        assertThat(targets()).isEmpty();
        stub.reset();

        collectionService.collectAll();

        assertThat(newBenefit(c).getStatus()).isEqualTo(NewBenefitStatus.READY);
        assertThat(targets()).containsExactly(tuple(users.match(), c));
    }

    @Test
    void 다시_수집해도_대상은_한_번만_고른다() {
        Users users = collectBaselineAndCreateUsers(C);
        stub.reset();
        collectionService.collectAll();
        Long c = benefitId(C);
        LocalDateTime selectedAt = newBenefit(c).getTargetsSelectedAt();

        collectionService.collectAll();
        selector.selectAll();

        assertThat(targets()).containsExactly(tuple(users.match(), c));
        assertThat(newBenefit(c).getTargetsSelectedAt()).isEqualTo(selectedAt);
    }

    @Test
    void 고른_뒤_프로필을_바꿔_해당하게_된_사용자는_대상이_아니다() {
        Users users = collectBaselineAndCreateUsers(C);
        stub.reset();
        collectionService.collectAll();
        saveProfile(users.otherRegion(), profile(YONGSAN));
        assertThat(result(users.otherRegion())).isEqualTo(MatchResult.MATCH);

        collectionService.collectAll();

        assertThat(targets()).containsExactly(tuple(users.match(), benefitId(C)));
    }

    @Test
    void 선정이_도중에_멈춘_뒤_다시_고르면_남은_새_혜택만_고른다() {
        Users users = collectBaselineAndCreateUsers(B, C);
        stub.reset();
        collectionService.collectAll();
        Long b = benefitId(B);
        Long c = benefitId(C);
        List<Tuple> targetsOfB = targets().stream().filter(target -> target.toList().get(1).equals(b)).toList();
        LocalDateTime selectedAtOfB = newBenefit(b).getTargetsSelectedAt();
        // B까지 고르고 C를 고르기 전에 멈춘 상태를 만든다. 혜택마다 대상과 선정 시각을 한 트랜잭션으로 저장한다.
        jdbcTemplate.update("DELETE FROM new_benefit_target WHERE benefit_id = ?", c);
        jdbcTemplate.update("UPDATE new_benefit SET targets_selected_at = NULL WHERE benefit_id = ?", c);

        selector.selectAll();

        assertThat(targets()).containsExactlyInAnyOrderElementsOf(
                Stream.concat(targetsOfB.stream(), Stream.of(tuple(users.match(), c))).toList());
        assertThat(newBenefit(b).getTargetsSelectedAt()).isEqualTo(selectedAtOfB);
        assertThat(newBenefit(c).getTargetsSelectedAt()).isNotNull();
    }

    /**
     * 기준선 수집에서 {@code serviceIds}를 빼고 사용자를 만든다. 다음 수집에서 되살리면 새 혜택이 된다.
     */
    private Users collectBaselineAndCreateUsers(String... serviceIds) {
        for (String serviceId : serviceIds) {
            stub.removeService(serviceId);
        }
        collectionService.collectAll();
        assertThat(newBenefitRepository.count()).isZero();
        Long match = createUser("match@example.com", true);
        saveProfile(match, profile(YONGSAN));
        Long otherRegion = createUser("other-region@example.com", true);
        saveProfile(otherRegion, profile(GANGNAM));
        Long noProfile = createUser("no-profile@example.com", true);
        Long notifyOff = createUser("notify-off@example.com", false);
        saveProfile(notifyOff, profile(YONGSAN));
        return new Users(match, otherRegion, noProfile, notifyOff);
    }

    /** C의 조건을 모두 충족하는 프로필. 거주 시군구만 받는다. */
    private static ProfileValues profile(String sigunguCode) {
        return new ProfileValues(LocalDate.of(1950, 1, 1), Gender.MALE, SEOUL, sigunguCode,
                Set.of(LifeAttribute.NONE), Set.of(HouseholdAttribute.NONE));
    }

    private Long createUser(String email, boolean newBenefitNotify) {
        UserAccount account = UserAccount.create(email, "{noop}x", LocalDateTime.now());
        account.changeNewBenefitNotify(newBenefitNotify);
        return userAccountRepository.save(account).getId();
    }

    private void saveProfile(Long userId, ProfileValues values) {
        LocalDateTime now = LocalDateTime.now();
        UserProfile profile = userProfileRepository.findWithConditionCodesByUserId(userId)
                .orElseGet(() -> UserProfile.create(userId, now));
        profile.replace(values, now);
        userProfileRepository.save(profile);
    }

    private MatchResult result(Long userId) {
        return matchService.match(userId, C).result();
    }

    private List<Tuple> targets() {
        return targetRepository.findAll().stream()
                .map(target -> tuple(target.getUserId(), target.getBenefitId()))
                .toList();
    }

    private NewBenefit newBenefit(Long benefitId) {
        return newBenefitRepository.findById(benefitId).orElseThrow();
    }

    private Long benefitId(String serviceId) {
        return benefitRepository.findByServiceId(serviceId).orElseThrow().getId();
    }

    /**
     * @param match       알림 켬, C에 해당
     * @param otherRegion 알림 켬, 다른 지역 기관이라 확인 필요
     * @param noProfile   알림 켬, 프로필 없음
     * @param notifyOff   알림 끔, C에 해당
     */
    private record Users(Long match, Long otherRegion, Long noProfile, Long notifyOff) {
    }
}
