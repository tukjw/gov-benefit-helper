package kr.govbenefit.collection.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬에서 실제 API로 수집을 확인할 때 쓴다. {@code collect} 프로필에서만 기동 시 한 번 실행한다.
 */
@Component
@Profile("collect")
@RequiredArgsConstructor
public class CollectionRunner implements ApplicationRunner {

    private final CollectionService collectionService;

    @Override
    public void run(ApplicationArguments args) {
        collectionService.collectAll();
    }
}
