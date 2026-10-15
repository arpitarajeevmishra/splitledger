package com.splitledger.architecture;

import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

/**
 * The module boundaries from HLD section 7, enforced rather than described. These are
 * the rules that make "modular monolith" a testable claim instead of a diagram
 * (ADR-001). Several pass trivially today and bite as the modules fill in — that is
 * the point of a baseline.
 */
@AnalyzeClasses(packages = "com.splitledger", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule no_cycles_between_modules =
            SlicesRuleDefinition.slices().matching("com.splitledger.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule common_must_not_depend_on_features =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("..common..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..identity..", "..group..", "..expense..", "..ledger..",
                            "..settlement..", "..payment..", "..notification..", "..outbox..")
                    .because("common is shared plumbing; a dependency on a feature inverts that");

    @ArchTest
    static final ArchRule controllers_must_not_touch_repositories =
            ArchRuleDefinition.noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                    .because("controllers call application services, which own the transaction");

    @ArchTest
    static final ArchRule ledger_knows_nothing_of_expenses_or_settlements =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("..ledger..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..expense..", "..settlement..")
                    .because("the ledger only posts balanced transactions; it must not know why")
                    // The ledger module arrives in S3. Until then this rule matches no classes,
                    // and ArchUnit fails empty rules by default -- which is the right default,
                    // because it catches a mistyped package name. Exempted here per-rule rather
                    // than by setting archRule.failOnEmptyShould=false globally, which would
                    // disable that protection for every rule. Remove this line in SL-302.
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule only_payment_may_talk_to_razorpay =
            ArchRuleDefinition.noClasses()
                    .that().resideOutsideOfPackage("..payment..")
                    .should().dependOnClassesThat().resideInAPackage("com.razorpay..")
                    .because("the gateway lives behind the PaymentProvider port (ADR-006)");

    @ArchTest
    static final ArchRule no_field_injection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule no_standard_streams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule no_java_util_logging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule no_legacy_date_types =
            ArchRuleDefinition.noClasses()
                    // jjwt's builder and clock APIs take java.util.Date. The conversion is
                    // confined to JwtService rather than allowed to spread, so the rule is
                    // narrowed by name instead of being deleted.
                    .that().doNotHaveSimpleName("JwtService")
                    .should().dependOnClassesThat()
                    .haveNameMatching("java\\.util\\.(Date|Calendar)")
                    .because("java.time only; mutable dates in a ledger are a bug waiting to happen");
}
