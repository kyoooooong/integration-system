dependencies {
    implementation(project(":application"))
    implementation(project(":domain"))

    // WebClient 만 필요하다. webflux 전체 스택이 아니다.
    implementation("org.springframework.boot:spring-boot-starter-webclient")
    // 공급사 DTO 가 직접 사용한다. 전이 의존에 기대지 않는다.
    implementation("tools.jackson.core:jackson-databind")

    implementation(libs.resilience4j.bulkhead)
    implementation(libs.resilience4j.reactor)

    // shaded 배포본을 쓴다. WireMock 이 끌고 오는 Jetty 가 우리 클래스패스에 섞이지 않는다.
    testImplementation(libs.wiremock.standalone)
}
