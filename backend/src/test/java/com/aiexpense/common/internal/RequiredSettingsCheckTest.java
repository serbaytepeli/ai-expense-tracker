package com.aiexpense.common.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RequiredSettingsCheckTest {

    @Test
    void namesEveryMissingVariable() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "${DB_URL}")
                .withProperty("spring.datasource.username", "")
                .withProperty("spring.datasource.password", "${DB_PASSWORD}");

        assertThat(RequiredSettingsCheck.missingVariables(environment))
                .containsExactly("DB_URL", "DB_USERNAME", "DB_PASSWORD");
    }

    @Test
    void failsStartupWithTheVariableNames() {
        RequiredSettingsCheck check = new RequiredSettingsCheck();
        check.setEnvironment(new MockEnvironment().withProperty("spring.datasource.username", "app"));

        assertThatIllegalStateException()
                .isThrownBy(() -> check.postProcessBeanFactory(null))
                .withMessageContaining("DB_URL, DB_PASSWORD")
                .withMessageNotContaining("DB_USERNAME");
    }

    @Test
    void passesWhenEverythingIsSet() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5433/x")
                .withProperty("spring.datasource.username", "app")
                .withProperty("spring.datasource.password", "not-a-real-password");

        assertThat(RequiredSettingsCheck.missingVariables(environment)).isEmpty();
    }
}
