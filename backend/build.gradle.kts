plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
}

group = "com.aiexpense"
version = "0.0.1-SNAPSHOT"
description = "AI Expense Tracker backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Every resolved dependency version is pinned in gradle.lockfile.
// Refresh after changing dependencies: ./gradlew resolveAndLockAll --write-locks
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    annotationProcessor(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))
    testRuntimeOnly(platform(libs.spring.boot.bom))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.springdoc.webmvc.ui)
    runtimeOnly(libs.postgresql)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // All lint warnings fail the build, except "processing" (unclaimed annotations are normal in Spring code).
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing", "-Werror"))
}

tasks.test {
    useJUnitPlatform {
        // Database tests are tagged "db". They need TEST_DB_URL/USERNAME/PASSWORD and FAIL
        // when those are missing. Excluding them is an explicit opt-in (-PnoDb), never silent.
        if (project.hasProperty("noDb")) {
            excludeTags("db")
        }
    }
    if (project.hasProperty("noDb")) {
        doFirst {
            logger.warn("WARNING: -PnoDb set: database tests (tag 'db') are NOT run. Report them as not run.")
        }
    }
    // Tests must really run every time: never restore results from the build cache and never
    // treat the task as up to date. Database tests depend on the environment (TEST_DB_*), which
    // Gradle does not see as an input, so a cached "pass" could hide tests that never ran.
    outputs.cacheIf("tests always execute") { false }
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

// Writes build/openapi.json (used by the contract check in T0.6). Needs TEST_DB_* like other db tests.
tasks.register<Test>("exportOpenApi") {
    description = "Writes the OpenAPI document to build/openapi.json."
    group = "documentation"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.aiexpense.openapi.OpenApiExportTest") }
    outputs.upToDateWhen { false }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// Resolves every resolvable configuration so `--write-locks` records all of them.
tasks.register("resolveAndLockAll") {
    description = "Resolves all configurations; run with --write-locks to refresh gradle.lockfile."
    notCompatibleWithConfigurationCache("resolves configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}
