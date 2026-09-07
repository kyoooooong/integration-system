plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":application"))
    // Composition Root 는 어댑터를 명시적으로 조립한다.
    // runtimeOnly 로 막으면 @Bean 조립이 불가능해진다.
    implementation(project(":storage"))
    implementation(project(":supplier-client"))
    implementation(project(":domain"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // WebClient 를 여기서 조립하므로 커넥터 설정을 위해 필요하다.
    implementation("org.springframework.boot:spring-boot-starter-webclient")
    implementation(libs.springdoc.webmvc.ui)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.archunit.junit5)
}
