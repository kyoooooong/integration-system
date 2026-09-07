package com.integration.stay.supplier;

import com.integration.stay.application.search.SearchResult;
import com.integration.stay.application.search.SearchTelemetry;
import com.integration.stay.domain.SupplierId;
import java.util.ArrayList;
import java.util.List;

/** 테스트에서 지표 발행을 관찰한다. Micrometer 를 끌어오지 않기 위해 포트를 직접 구현한다. */
class RecordingTelemetry implements SearchTelemetry {

    record Rejection(SupplierId supplierId, String reason, int count) {}

    final List<Rejection> rejections = new ArrayList<>();
    final List<Throwable> pipelineFailures = new ArrayList<>();

    @Override
    public void adapterPipelineFailed(SupplierId supplierId, Throwable cause) {
        pipelineFailures.add(cause);
    }

    @Override
    public void offersRejected(SupplierId supplierId, String reason, int count) {
        rejections.add(new Rejection(supplierId, reason, count));
    }

    @Override
    public void searchCompleted(SearchResult result) {
        // 이 테스트들은 어댑터 단위라 SearchResult 를 만들지 않는다.
    }
}
