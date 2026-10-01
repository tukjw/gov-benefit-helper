package kr.govbenefit.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import kr.govbenefit.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 수집은 스케줄러와 collect 프로필 실행기로만 시작하고, HTTP로는 실행할 수 없다.
 */
@SpringBootTest(properties = "gov24.schedule.enabled=false")
@Import(TestcontainersConfiguration.class)
class CollectionAccessTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 수집을_실행하는_HTTP_경로가_없다() {
        assertThat(handlerMapping.getHandlerMethods().values())
                .noneMatch(handler -> handler.getBeanType().getPackageName().startsWith("kr.govbenefit.collection"));
    }

    @Test
    void 정기_수집을_끄면_스케줄러가_없다() {
        assertThat(applicationContext.getBeanNamesForType(CollectionScheduler.class)).isEmpty();
    }
}
