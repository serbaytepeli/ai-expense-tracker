package com.aiexpense;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI Expense Tracker backend: a modular monolith. Each direct subpackage is a module
 * (see docs/architecture.md section 3); module boundaries are enforced by ArchUnit tests.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiExpenseApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiExpenseApplication.class, args);
    }
}
