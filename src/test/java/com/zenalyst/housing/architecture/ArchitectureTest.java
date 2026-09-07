package com.zenalyst.housing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules that are checked by the build rather than by reviewers remembering them.
 *
 * <p>The important one is the purity of {@code allocation}. The entire defensibility of this
 * system rests on the claim that the allocator is a pure function of (frozen registry, rule
 * version, seed) — that anyone, anywhere, can re-derive the same 600 names. A single
 * {@code Instant.now()}, injected repository or {@code Math.random()} inside that package
 * would quietly make the claim false while every test still passed. So it is enforced here.
 */
class ArchitectureTest {

    private static final String ROOT = "com.zenalyst.housing";
    private static final String ALLOCATION = ROOT + ".allocation..";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("the allocator must not depend on Spring")
    void allocationIsFreeOfSpring() {
        noClasses().that().resideInAPackage(ALLOCATION)
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .because("the allocator must be runnable, and therefore verifiable, without an application context")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("the allocator must not depend on persistence")
    void allocationIsFreeOfPersistence() {
        noClasses().that().resideInAPackage(ALLOCATION)
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.hibernate..", "javax.sql..", "java.sql..")
                .because("the allocator consumes a frozen snapshot; if it can reach the database it can read data that was not frozen")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("the allocator must not read the clock")
    void allocationDoesNotReadTheClock() {
        noClasses().that().resideInAPackage(ALLOCATION)
                .should().callMethod(java.time.Instant.class, "now")
                .orShould().callMethod(java.time.LocalDate.class, "now")
                .orShould().callMethod(java.time.LocalDateTime.class, "now")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().callMethod(System.class, "nanoTime")
                .because("an allocation that depends on when it ran cannot be reproduced later")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("the allocator must not use ambient randomness")
    void allocationDoesNotUseAmbientRandomness() {
        noClasses().that().resideInAPackage(ALLOCATION)
                .should().callMethod(Math.class, "random")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.Random")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.SecureRandom")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.ThreadLocalRandom")
                .because("all randomness enters through the published seed, or the draw is not verifiable")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("no cyclic dependencies between feature packages")
    void noPackageCycles() {
        SlicesRuleDefinition.slices()
                .matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .allowEmptyShould(true)
                .check(classes);
    }
}
