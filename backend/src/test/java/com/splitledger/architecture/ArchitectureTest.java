package com.splitledger.architecture;

import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
                    // Anchored at com.splitledger rather than written as "..payment..":
                    // the loose form matches any package segment with that name, so a
                    // shared value type living at common.payment would trip the rule by
                    // referring to itself. The rule is about the feature modules, and now
                    // says so.
                    .resideInAnyPackage("com.splitledger.identity..", "com.splitledger.group..",
                            "com.splitledger.expense..", "com.splitledger.ledger..",
                            "com.splitledger.settlement..", "com.splitledger.payment..",
                            "com.splitledger.notification..", "com.splitledger.outbox..")
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
                    .because("the ledger only posts balanced transactions; it must not know why");

    /**
     * Kept, and currently vacuous: SL-601 implements the Orders call with {@code RestClient}
     * rather than Razorpay's SDK, so no class named {@code com.razorpay.*} exists to depend
     * on. It stays because the day someone adds the SDK is the day it starts mattering, and
     * a rule that passes for the right reason tomorrow is cheaper than remembering to write
     * it then.
     */
    @ArchTest
    static final ArchRule only_payment_may_talk_to_razorpay =
            ArchRuleDefinition.noClasses()
                    .that().resideOutsideOfPackage("..payment..")
                    .should().dependOnClassesThat().resideInAPackage("com.razorpay..")
                    .because("the gateway lives behind the PaymentProvider port (ADR-006)");

    /**
     * The rule that actually bites today. Without an SDK to point at, "the gateway stays
     * behind the port" means our own gateway classes: the provider, its properties, and
     * whatever S7 adds for signatures and webhooks.
     *
     * <p>Anchored at {@code com.splitledger.payment..} rather than {@code ..payment..} for
     * the reason recorded above the {@code common} rule.
     */
    @ArchTest
    static final ArchRule nothing_outside_payment_knows_the_gateways_name =
            ArchRuleDefinition.noClasses()
                    .that().resideOutsideOfPackage("com.splitledger.payment..")
                    .should().dependOnClassesThat().haveSimpleNameStartingWith("Razorpay")
                    .because("settlement asks the PaymentProvider port and never learns "
                            + "which provider answered (ADR-006)");

    /**
     * Spring cannot choose between two constructors unless one is marked, and the failure
     * is a runtime {@code NoSuchMethodException: <init>()} that only appears when a context
     * loads -- which, with no local database, means only in CI.
     *
     * <p>This project keeps a second constructor on several services so a {@code Clock} or
     * {@code TimeMeter} can be injected and time-dependent behaviour tested deterministically.
     * That pattern is worth keeping, so the rule enforces the annotation rather than banning
     * the pattern. Written after making the same mistake twice.
     */
    @ArchTest
    static final ArchRule spring_beans_with_two_constructors_must_mark_one =
            ArchRuleDefinition.classes()
                    .that(new DescribedPredicate<JavaClass>(
                            "are Spring-managed and declare more than one constructor") {
                        @Override
                        public boolean test(JavaClass clazz) {
                            boolean managed = clazz.isAnnotatedWith(Component.class)
                                    || clazz.isMetaAnnotatedWith(Component.class);
                            return managed && clazz.getConstructors().size() > 1;
                        }
                    })
                    .should(new ArchCondition<JavaClass>(
                            "annotate exactly one constructor with @Autowired") {
                        @Override
                        public void check(JavaClass clazz, ConditionEvents events) {
                            long marked = clazz.getConstructors().stream()
                                    .filter(c -> c.isAnnotatedWith(Autowired.class))
                                    .count();
                            if (marked != 1) {
                                events.add(SimpleConditionEvent.violated(clazz,
                                        clazz.getName() + " declares "
                                                + clazz.getConstructors().size()
                                                + " constructors but " + marked
                                                + " are annotated @Autowired; Spring will fail"
                                                + " at context load with NoSuchMethodException"));
                            }
                        }
                    });

    /**
     * The sibling mistake, and the one that got through.
     *
     * <p>There is no {@code Clock} bean in this application, deliberately: services take
     * {@code Clock.systemUTC()} and accept a fixed clock only through a package-private
     * constructor for tests. A constructor that Spring will actually call and that asks for
     * a {@code Clock} therefore fails the whole context -- and fails it where no unit test
     * looks, because none of them load one. It cost a red CI run on SL-701.
     *
     * <p>The rule checks the constructor Spring will choose: the only one, or the
     * {@code @Autowired} one where there are several. The second constructor is exactly
     * where a {@code Clock} is supposed to be.
     */
    @ArchTest
    static final ArchRule the_constructor_spring_uses_must_not_ask_for_a_clock =
            ArchRuleDefinition.classes()
                    .that(new DescribedPredicate<JavaClass>("are Spring-managed") {
                        @Override
                        public boolean test(JavaClass clazz) {
                            return clazz.isAnnotatedWith(Component.class)
                                    || clazz.isMetaAnnotatedWith(Component.class);
                        }
                    })
                    .should(new ArchCondition<JavaClass>(
                            "not take a Clock on the constructor Spring calls") {
                        @Override
                        public void check(JavaClass clazz, ConditionEvents events) {
                            clazz.getConstructors().stream()
                                    .filter(c -> clazz.getConstructors().size() == 1
                                            || c.isAnnotatedWith(Autowired.class))
                                    .filter(c -> c.getRawParameterTypes().stream()
                                            .anyMatch(t -> t.getName().equals("java.time.Clock")))
                                    .forEach(c -> events.add(SimpleConditionEvent.violated(clazz,
                                            clazz.getName() + " asks Spring for a java.time.Clock"
                                                    + ", and no such bean exists; take"
                                                    + " Clock.systemUTC() here and keep the Clock"
                                                    + " on a package-private constructor")));
                        }
                    });

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
