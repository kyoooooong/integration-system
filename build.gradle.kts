plugins {
    id("org.springframework.boot") version "4.1.1" apply false
    id("com.diffplug.spotless") version "6.25.0" apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    /**
     * 포맷터를 붙이지 않는다. 위생 항목만 강제한다.
     *
     * googleJavaFormat 을 실제로 적용해 보고 뺐다 — 115개 파일이 5,300줄 바뀌었다.
     * 들여쓰기가 4에서 2로 전면 변경되는 것보다 더 나쁜 것은 javadoc 재배치였다.
     * 그 포맷터의 100컬럼 제한은 "문자 수" 기준인데 한글은 폭이 2다.
     * 손으로 줄바꿈한 한글 주석이 100자로 재배치되면 화면상 200컬럼이 되어
     * 오히려 읽기 어려워진다. 이 저장소는 주석에 설계 근거를 담고 있어서 그 손해가 크다.
     *
     * 아래 셋은 사람이 판단할 여지가 없는 것들이라 기계가 강제해도 잃는 것이 없다.
     * importOrder 는 실제로 제 역할을 했다 — 손으로 import 를 끼워 넣다 순서를 틀려
     * 컴파일이 깨진 적이 있다.
     */
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            importOrder()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        // 재현성은 로컬 JDK 버전이 아니라 toolchain 으로 보장한다.
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        // BOM 은 코드를 끌어오지 않고 버전만 정렬한다. domain 의 "의존성 0" 을 깨지 않는다.
        add("implementation", platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
        add("testImplementation", platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))

        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testImplementation", "org.assertj:assertj-core")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        // deprecation 을 침묵시키지 않는다. testcontainers 2.x 의 패키지 이동을
        // 이 경고 하나가 잡아냈다.
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
