plugins {
    // api configuration 은 java-library 가 제공한다. java 플러그인만으로는 빌드가 깨진다.
    `java-library`
}

dependencies {
    // 포트 반환 타입에 도메인이 노출된다.
    api(project(":domain"))
    // 포트 시그니처에 Mono 가 노출된다. Reactor 는 Spring 이 아니라 별도 라이브러리다.
    api("io.projectreactor:reactor-core")
}
