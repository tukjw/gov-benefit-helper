package kr.govbenefit.collection.domain;

public enum CollectionStatus {
    RUNNING,
    SUCCEEDED,
    /** 실패한 쪽이 있거나 도중에 중단됐다. 실행은 목록 1쪽을 저장한 경우, 경로는 1쪽을 저장한 경우다. */
    PARTIAL,
    FAILED
}
