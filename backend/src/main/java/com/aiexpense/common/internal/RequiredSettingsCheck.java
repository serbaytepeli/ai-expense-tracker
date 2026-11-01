package com.aiexpense.common.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Stops startup before any bean is created when a required connection setting is missing,
 * naming the environment variables to set. Without it the failure surfaces later as an
 * unclear driver error ("'url' must start with jdbc").
 */
@Component
public class RequiredSettingsCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    /** Spring property -> environment variable that provides it (see application.yml). */
    static final Map<String, String> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("spring.datasource.url", "DB_URL");
        REQUIRED.put("spring.datasource.username", "DB_USERNAME");
        REQUIRED.put("spring.datasource.password", "DB_PASSWORD");
    }

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> missing = missingVariables(environment);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required environment variables: " + String.join(", ", missing)
                    + ". See .env.example (in Claude Code Cloud: eval \"$(scripts/dev-db.sh env)\").");
        }
    }

    static List<String> missingVariables(Environment environment) {
        List<String> missing = new ArrayList<>();
        REQUIRED.forEach((property, variable) -> {
            if (!isSet(environment, property)) {
                missing.add(variable);
            }
        });
        return missing;
    }

    private static boolean isSet(Environment environment, String property) {
        try {
            String value = environment.getProperty(property);
            return value != null && !value.isBlank() && !value.contains("${");
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            return false;
        }
    }
}
