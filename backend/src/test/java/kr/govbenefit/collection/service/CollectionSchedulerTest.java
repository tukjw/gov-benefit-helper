package kr.govbenefit.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import kr.govbenefit.TestcontainersConfiguration;
import kr.govbenefit.collection.Gov24StubServer;
import kr.govbenefit.collection.domain.CollectionStatus;
import kr.govbenefit.collection.repository.CollectionRunRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 정기 수집이 하루 1회로 등록되고, 실행되면 전체 수집을 한 번 한다.
 */
@SpringBootTest(properties = {"gov24.schedule.enabled=true", "gov24.per-page=2"})
@Import(TestcontainersConfiguration.class)
class CollectionSchedulerTest {

    private static final Gov24StubServer stub = Gov24StubServer.start();

    @DynamicPropertySource
    static void gov24Properties(DynamicPropertyRegistry registry) {
        registry.add("gov24.base-url", stub::baseUrl);
        registry.add("gov24.service-key", () -> "stub-service-key-5c7d10");
    }

    @AfterAll
    static void closeStub() {
        stub.close();
    }

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;
    @Autowired
    private CollectionScheduler collectionScheduler;
    @Autowired
    private CollectionRunRepository runRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 매일_04시_수집이_등록된다() {
        List<CronTask> collectionTasks = scheduledTaskHolder.getScheduledTasks().stream()
                .map(scheduled -> scheduled.getTask())
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                // 등록된 Runnable은 실행 결과 추적용으로 감싸져 있고, toString이 원래 메서드 이름을 돌려준다.
                .filter(task -> task.toString().equals(CollectionScheduler.class.getName() + ".collectDaily"))
                .toList();

        assertThat(collectionTasks).singleElement()
                .satisfies(task -> assertThat(task.getExpression()).isEqualTo("0 0 4 * * *"));
    }

    @Test
    void 정기_수집은_전체_수집을_한_번_실행한다() {
        for (String table : List.of("benefit_detail", "benefit", "source_record_history", "source_record",
                "collection_quarantine", "collection_failure", "collection_run_step", "collection_run")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }

        collectionScheduler.collectDaily();

        assertThat(runRepository.findAll()).singleElement()
                .satisfies(run -> assertThat(run.getStatus()).isEqualTo(CollectionStatus.SUCCEEDED));
    }

    @Test
    void 인증키가_없으면_정기_수집을_건너뛴다() {
        CollectionService collectionService = mock(CollectionService.class);
        given(collectionService.collectAll()).willThrow(new IllegalStateException("인증키 없음"));

        assertThatCode(() -> new CollectionScheduler(collectionService).collectDaily()).doesNotThrowAnyException();
    }
}
