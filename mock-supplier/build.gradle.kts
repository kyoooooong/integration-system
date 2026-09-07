plugins {
    id("org.springframework.boot")
}

// 본 애플리케이션 코드와 물리적으로 분리된 별도 프로세스다. 어떤 모듈에도 의존하지 않는다.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
