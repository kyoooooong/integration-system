package com.integration.stay.app;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 경계 규칙을 빌드가 강제한다.
 *
 * <p>규칙을 적게 유지한다. 공급사별 분기 금지 같은 것을 복잡한 바이트코드 술어로 잡지
 * 않는다 — 모듈 의존 방향이 대부분을 막고 나머지는 리뷰로 충분하다.
 * <b>테스트도 과설계할 수 있다.</b>
 *
 * <p>여기 있는 규칙의 기준은 하나다. <b>어겼을 때 컴파일이 되고 테스트가 통과하며
 * 런타임에도 아무 일이 없는가.</b> 그런 것만 여기서 잡는다. 어기면 바로 깨지는 것은
 * 규칙으로 만들 이유가 없다.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.integration.stay");
    }

    @Test
    @DisplayName("domain 은 프레임워크에 의존하지 않는다")
    void domain_은_프레임워크에_의존하지_않는다() {
        // 빌드 파일에도 의존성이 없지만, 여기서 한 번 더 잡는 이유는 누군가 build.gradle.kts 에
        // 한 줄을 추가했을 때 그 결과가 컴파일 성공으로 끝나지 않게 하기 위해서다.
        noClasses()
                .that()
                .resideInAPackage("com.integration.stay.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "com.fasterxml.jackson..",
                        "tools.jackson..",
                        "io.micrometer..",
                        "reactor..",
                        "java.sql..",
                        "javax.sql..",
                        "jakarta.persistence..")
                .check(classes);
    }

    @Test
    @DisplayName("공급사 DTO 는 자기 모듈 밖으로 나가지 않는다")
    void 공급사_DTO는_자기_모듈_밖으로_나가지_않는다() {
        // ACL 의 존재 이유가 이 한 줄이다. 공급사 응답 형식이 도메인이나 API 로 새면
        // 공급사 추가가 전 계층 수정이 된다.
        noClasses()
                .that()
                .resideInAnyPackage(
                        "com.integration.stay.domain..",
                        "com.integration.stay.application..",
                        "com.integration.stay.storage..",
                        "com.integration.stay.app..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.integration.stay.supplier.a..", "com.integration.stay.supplier.b..")
                .because("공급사별 DTO 와 Normalizer 는 supplier-client 안에만 존재해야 한다. "
                        + "Composition Root 의 조립은 config 패키지에서만 허용한다")
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        // 조립은 예외다. 어댑터를 명시적으로 new 하는 곳이 한 군데 있어야 한다.
                        .withImportOption(location -> !location.contains("/app/global/config/"))
                        .importPackages("com.integration.stay"));
    }

    @Test
    @DisplayName("검색 경로에서 block 을 호출하지 않는다")
    void 검색_경로에서_block을_호출하지_않는다() {
        // 검색은 이벤트 루프 위에서 돈다. 여기서 블로킹하면 그 루프에 물린 다른 공급사의
        // 응답 처리까지 멈춘다. MVC 비동기 타임아웃은 HTTP 요청 수명주기를 끊을 뿐
        // 막힌 Netty 스레드를 선점하지 못한다.
        // 카탈로그 동기화는 예외다 — 스케줄러 스레드에서 순차 실행되는 배경 작업이다.
        noClasses()
                .that()
                .resideInAnyPackage("com.integration.stay.supplier..", "com.integration.stay.application..")
                .and()
                .haveSimpleNameNotContaining("Catalog")
                .should()
                .callMethodWhere(target(name("block"))
                        .and(target(owner(describe(
                                "reactor Publisher",
                                (JavaClass owner) -> owner.getName().startsWith("reactor.core.publisher.")))))
                        .as("call block() on a reactor Publisher"))
                .check(classes);
    }

    @Test
    @DisplayName("패키지 사이에 순환 참조가 없다")
    void 패키지_사이에_순환_참조가_없다() {
        // 모듈 사이의 순환은 Gradle 이 이미 막는다. 여기서 잡는 것은 한 모듈 안의
        // 패키지 순환이다 — supplier.a 가 supplier 를 쓰고 supplier 가 다시 supplier.a 를
        // 쓰는 식의 구조는 컴파일은 되지만 어느 쪽이 어느 쪽의 추상인지 알 수 없게 만든다.
        SlicesRuleDefinition.slices()
                .matching("com.integration.stay.(**)")
                .should()
                .beFreeOfCycles()
                .check(classes);
    }

    @Test
    @DisplayName("정규화는 순수 함수 영역이다")
    void 정규화는_순수_함수_영역이다() {
        // "Normalizer 는 순수 함수" 는 문서에 여러 번 적어 둔 주장이다.
        // 주장을 문서에만 두면 다음 사람이 편의상 여기서 한 번만 더 호출하게 되고,
        // 그 순간 정규화 테스트에 WireMock 이 필요해지면서 밀리초 단위 테스트가 사라진다.
        // 실패 분류도 어댑터가 아니라 정규화 안에서 일어나기 시작한다.
        noClasses()
                .that()
                .resideInAPackage("com.integration.stay.supplier.normalize..")
                .or()
                .haveSimpleNameEndingWith("Normalizer")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("reactor..", "org.springframework..", "java.net..", "io.micrometer..")
                .because("정규화는 입력만으로 결과가 정해져야 한다. I/O 나 지표 발행이 들어오면 "
                        + "컨테이너 없이 도는 테스트가 사라지고 실패 귀속도 어댑터 밖으로 샌다")
                .check(classes);
    }

    @Test
    @DisplayName("global 은 유스케이스 패키지를 모른다")
    void global_은_유스케이스_패키지를_모른다() {
        // global 은 여러 유스케이스가 함께 쓰는 것(오류 변환, 조립, 설정)만 둔다.
        // 여기서 search 를 참조하기 시작하면 유스케이스를 추가할 때마다 global 이 커지고,
        // 결국 "공통" 이라는 이름의 두 번째 애플리케이션이 된다.
        // 방향은 한쪽이다 — search 가 global.error 를 참조하는 것은 허용한다.
        noClasses()
                .that()
                .resideInAPackage("com.integration.stay.app.global..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.integration.stay.app.search..")
                .check(classes);
    }

    @Test
    @DisplayName("요청 DTO 와 응답 DTO 는 서로를 참조하지 않는다")
    void 요청_DTO와_응답_DTO는_서로를_참조하지_않는다() {
        // 나눠 두기만 하면 이름만 나뉜다. 한쪽이 다른 쪽의 타입을 재사용하기 시작하면
        // 입력 계약을 고칠 때 출력 계약이 함께 바뀌고, 그때는 이미 되돌리기 어렵다.
        noClasses()
                .that()
                .resideInAPackage("com.integration.stay.app.search.dto.request..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.integration.stay.app.search.dto.response..")
                .check(classes);

        noClasses()
                .that()
                .resideInAPackage("com.integration.stay.app.search.dto.response..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.integration.stay.app.search.dto.request..")
                .check(classes);
    }

    @Test
    @DisplayName("컨트롤러는 어댑터에 의존하지 않는다")
    void 컨트롤러는_어댑터에_의존하지_않는다() {
        noClasses()
                .that()
                .resideInAPackage("com.integration.stay.app.search..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.integration.stay.supplier..", "com.integration.stay.storage..")
                .because("컨트롤러는 application 의 유스케이스와 도메인만 알아야 한다")
                .check(classes);
    }
}
