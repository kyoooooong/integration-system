dependencies {
    implementation(project(":application"))
    implementation(project(":domain"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // UNIQUE 제약, ON CONFLICT DO UPDATE, 트랜잭션 롤백, 식별자 안정성을
    // 실제 PostgreSQL 로 증명한다. 매핑 영속성에 대한 주장이 전부 여기 걸려 있다.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
