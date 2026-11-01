package com.aiexpense.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.base.DescribedPredicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Enforces the modular monolith rules of docs/architecture.md section 3.1-3.2:
 * <ul>
 *   <li>a module may only depend on the modules listed for it;</li>
 *   <li>another module's code may only be used through its root package (public API);</li>
 *   <li>no cycles between modules.</li>
 * </ul>
 */
class ModuleBoundariesTest {

    static final String ROOT = "com.aiexpense";

    /** Allowed dependencies per module (docs/architecture.md section 3.1). */
    static final Map<String, Set<String>> ALLOWED = Map.of(
            "common", Set.of(),
            "auth", Set.of("user", "category", "common"),
            "user", Set.of("common"),
            "category", Set.of("common"),
            "expense", Set.of("category", "user", "common"),
            "report", Set.of("category", "user", "common"),
            "insight", Set.of("report", "user", "common"));

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    static Stream<String> modules() {
        return ALLOWED.keySet().stream().sorted();
    }

    @ParameterizedTest(name = "module {0} only uses allowed modules through their public API")
    @MethodSource("modules")
    void moduleOnlyDependsOnAllowedModulesPublicApi(String module) {
        List<DescribedPredicate<? super JavaClass>> allowedTargets = new ArrayList<>();
        allowedTargets.add(resideOutsideOfPackage(ROOT + ".."));
        allowedTargets.add(resideInAPackage(ROOT + "." + module + ".."));
        for (String other : ALLOWED.get(module)) {
            // Root package only: subpackages of another module are private to it.
            allowedTargets.add(resideInAPackage(ROOT + "." + other));
        }
        DescribedPredicate<JavaClass> allowed = DescribedPredicate.or(allowedTargets)
                .as("classes outside com.aiexpense, in module '%s', or in the root package of %s",
                        module, ALLOWED.get(module));

        classes().that().resideInAPackage(ROOT + "." + module + "..")
                .should().onlyDependOnClassesThat(allowed)
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    @Test
    void everyPackageBelongsToAKnownModule() {
        classes().that().resideInAPackage(ROOT + "..")
                .and(not(resideInAPackage(ROOT)))
                .should().resideInAnyPackage(ALLOWED.keySet().stream()
                        .map(m -> ROOT + "." + m + "..").toArray(String[]::new))
                .because("new top-level packages are new modules and must be added to docs/architecture.md first")
                .check(productionClasses);
    }

    @Test
    void modulesDoNotDependOnTheApplicationRootPackage() {
        classes().that().resideInAnyPackage(ALLOWED.keySet().stream()
                        .map(m -> ROOT + "." + m + "..").toArray(String[]::new))
                .should().onlyDependOnClassesThat(not(resideInAPackage(ROOT)))
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    @Test
    void modulesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    @Test
    void allowedTableOnlyNamesKnownModules() {
        // Guards the table above against typos (a module allowed to use an unknown module).
        ALLOWED.forEach((module, targets) -> targets.forEach(target -> {
            if (!ALLOWED.containsKey(target)) {
                throw new AssertionError("Module '" + module + "' lists unknown module '" + target + "'");
            }
        }));
    }
}
