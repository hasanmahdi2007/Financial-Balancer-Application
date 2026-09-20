package com.hasan.budget;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.hasan.budget.planning.domain.AllocationStrategy;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;

/**
 * Enforces the structural promises the project makes about itself.
 *
 * <p>These are not generic layering dogma. Most of them encode a specific mistake this codebase has
 * already made or come close to making, which is why each carries its reasoning rather than a bare
 * assertion.
 */
@AnalyzeClasses(packages = "com.hasan.budget", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // Declared before the rules that use them: these fields are read during static initialisation,
    // which runs in textual order, so a later declaration would still be null when a rule is built.

    private static final DescribedPredicate<JavaCall<?>> READS_A_SYSTEM_CLOCK =
            new DescribedPredicate<>("reads a system clock") {
                @Override
                public boolean test(JavaCall<?> call) {
                    String owner = call.getTargetOwner().getFullName();
                    String method = call.getName();
                    boolean timeNow = owner.startsWith("java.time.") && "now".equals(method);
                    boolean systemClock = "java.lang.System".equals(owner)
                            && ("currentTimeMillis".equals(method) || "nanoTime".equals(method));
                    return timeNow || systemClock;
                }
            };

    private static final ArchCondition<JavaField> BE_STORED_AS_AN_ORDINAL =
            new ArchCondition<>("be stored as an ordinal") {
                @Override
                public void check(JavaField field, ConditionEvents events) {
                    boolean ordinal = field.getAnnotationOfType(Enumerated.class).value() == EnumType.ORDINAL;
                    events.add(new SimpleConditionEvent(
                            field, ordinal, field.getFullName() + " is stored as an ordinal"));
                }
            };

    /**
     * The allocation engine is exhaustively testable in milliseconds precisely because it needs no
     * container, no database and no network. Every one of these imports would take that away.
     */
    @ArchTest
    static final ArchRule allocationEngineIsFrameworkFree = noClasses()
            .that()
            .resideInAPackage("..planning.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "javax..",
                    "com.fasterxml.jackson..",
                    "java.sql..",
                    "..costofliving..",
                    "..profile..",
                    "..ingestion..",
                    "..web..",
                    "..persistence..")
            .because("the planning domain must stay pure so its tests need no context and no I/O");

    /** Shared value types are depended on by everything, so they may depend on nothing of ours. */
    @ArchTest
    static final ArchRule sharedDependsOnNothingOfOurs = noClasses()
            .that()
            .resideInAPackage("com.hasan.budget.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.hasan.budget.planning..",
                    "com.hasan.budget.costofliving..",
                    "com.hasan.budget.profile..",
                    "com.hasan.budget.ingestion..",
                    "org.springframework..",
                    "jakarta..")
            .because("shared sits at the bottom of the dependency graph and must have no edges upward");

    /**
     * The rule a dependency check alone would miss. A domain class that reads the system clock is
     * not reproducible: the same inputs stop producing the same plan tomorrow. The evaluation date
     * arrives as a parameter instead, which is why {@code AllocationRequest} carries {@code asOf}.
     */
    @ArchTest
    static final ArchRule domainNeverReadsTheClock = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .callMethodWhere(READS_A_SYSTEM_CLOCK)
            .because("results must be reproducible, so the evaluation date is always passed in");

    /** Dependencies point inward: a domain type must never know about what is delivering it. */
    @ArchTest
    static final ArchRule domainDoesNotDependOnOuterLayers = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application..", "..web..", "..persistence..", "..classpath..")
            .because("the domain is the innermost layer and nothing outside it may leak in");

    /** Persistence is an implementation detail owned by its module's application services. */
    @ArchTest
    static final ArchRule persistenceIsReachedOnlyThroughApplication = noClasses()
            .that()
            .resideOutsideOfPackages("..persistence..", "..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..persistence..")
            .because("JPA entities must not escape the module that owns them");

    /**
     * {@code BigDecimal.equals} is scale-sensitive, so 1.5 and 1.50 compare unequal, and binary
     * floating point cannot represent most decimal amounts at all. {@code Money} exists to contain
     * both problems in one place; a raw numeric field in the domain means it escaped.
     */
    @ArchTest
    static final ArchRule currencyNeverUsesRawNumbers = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..domain..")
            .should()
            .haveRawType(BigDecimal.class)
            .orShould()
            .haveRawType(double.class)
            .orShould()
            .haveRawType(float.class)
            .because("money is Money; raw decimals reintroduce the scale and rounding bugs it prevents");

    /** A port is a contract. The moment it is a class, the thing behind it stops being swappable. */
    @ArchTest
    static final ArchRule portsAreInterfaces = classes()
            .that()
            .resideInAPackage("..port..")
            .should()
            .beInterfaces()
            .because("ports exist so an implementation can be replaced without touching callers");

    /**
     * The narrowest and most valuable seam in the project. The allocator receives a single
     * {@code Money} surplus and knows nothing about how it was computed. Letting it see the
     * breakdown would widen that contract "for convenience" and never be narrowed again.
     */
    @ArchTest
    static final ArchRule allocatorCannotSeeHowTheSurplusWasBuilt = noClasses()
            .that()
            .areAssignableTo(AllocationStrategy.class)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..planning.domain.surplus..")
            .because("the allocator takes a surplus, never the reasoning that produced it");

    /**
     * An ordinal column plus a later reordering of the enum is silent data corruption: existing rows
     * quietly change meaning with no error anywhere. Note that a bare {@code @Enumerated} defaults
     * to ORDINAL, so the omission is the trap this catches, not just the explicit choice.
     */
    @ArchTest
    static final ArchRule enumsPersistByName = noFields()
            .that()
            .areAnnotatedWith(Enumerated.class)
            .should(BE_STORED_AS_AN_ORDINAL)
            .because("reordering an enum must never silently change what stored rows mean");
}
