package com.integration.stay.app.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 문서 메타데이터.
 *
 * <p>스키마는 컨트롤러 시그니처와 응답 record 에서 자동으로 나온다. 애노테이션으로
 * 스키마를 다시 적지 않는다 — 코드와 애노테이션이 갈라지면 문서가 조용히 거짓말을 한다.
 * 여기에는 <b>코드에서 유도할 수 없는 것</b>만 적는다.
 */
@Configuration
class OpenApiConfiguration {

    @Bean
    OpenAPI stayIntegrationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Multi-Supplier Stay Aggregator")
                        .version("v1")
                        .description(
                                """
                                여러 외부 숙박 공급사의 상품을 하나의 표준 모델로 통합해 검색을 제공한다.

                                응답의 `partial` 이 true 이면 일부 공급사 조회가 실패한 것이며,
                                `suppliers` 배열에 공급사별 상태와 사유가 담긴다.

                                상태 코드는 결과의 사용 가능 여부를 나타낸다.
                                200 은 쓸 만한 공급사가 하나라도 있다는 뜻이고, 결과가 0건이어도 200 이다
                                (전 숙소 만실인 경우). 503 은 조회 자체가 실패했다는 뜻이다.
                                """));
    }
}
