package com.aiexpense.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Project-wide coding rules from CLAUDE.md that can be checked mechanically. */
class CodingRulesTest {

    private static final Set<String> FLOATING_POINT_TYPES =
            Set.of("double", "float", Double.class.getName(), Float.class.getName());

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aiexpense");
    }

    /**
     * CLAUDE.md rule 1: money is never float/double. The rule is applied to all production code
     * (fields, parameters, return types) so a money value cannot slip through as a "non-money" double.
     */
    @Test
    void noFloatingPointTypesInProductionCode() {
        classes().should(notUseFloatingPointTypes())
                .because("money must be BigDecimal (CLAUDE.md rule 1)")
                .check(productionClasses);
    }

    /**
     * CLAUDE.md: time comes from the injected Clock. Calling now() without a Clock, or creating
     * a system clock, is only allowed in the class that defines the Clock bean.
     */
    @Test
    void currentTimeOnlyThroughInjectedClock() {
        noClasses().that().doNotHaveFullyQualifiedName("com.aiexpense.common.internal.ClockConfiguration")
                .should().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalDateTime.class, "now")
                .orShould().callMethod(LocalTime.class, "now")
                .orShould().callMethod(Instant.class, "now")
                .orShould().callMethod(ZonedDateTime.class, "now")
                .orShould().callMethod(OffsetDateTime.class, "now")
                .orShould().callMethod(YearMonth.class, "now")
                .orShould().callMethod(Year.class, "now")
                .orShould().callMethod(Clock.class, "systemUTC")
                .orShould().callMethod(Clock.class, "systemDefaultZone")
                .because("time must come from the injected java.time.Clock (CLAUDE.md, backend rules)")
                .check(productionClasses);
    }

    private static ArchCondition<JavaClass> notUseFloatingPointTypes() {
        return new ArchCondition<>("not use float/double in fields, parameters or return types") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaField field : javaClass.getFields()) {
                    if (FLOATING_POINT_TYPES.contains(field.getRawType().getName())) {
                        events.add(SimpleConditionEvent.violated(field, field.getFullName() + " is " + field.getRawType().getName()));
                    }
                }
                for (JavaCodeUnit codeUnit : javaClass.getCodeUnits()) {
                    if (FLOATING_POINT_TYPES.contains(codeUnit.getRawReturnType().getName())) {
                        events.add(SimpleConditionEvent.violated(codeUnit, codeUnit.getFullName() + " returns a floating point type"));
                    }
                    codeUnit.getRawParameterTypes().stream()
                            .filter(type -> FLOATING_POINT_TYPES.contains(type.getName()))
                            .forEach(type -> events.add(SimpleConditionEvent.violated(
                                    codeUnit, codeUnit.getFullName() + " has a " + type.getName() + " parameter")));
                }
            }
        };
    }
}
