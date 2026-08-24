package nl.logius.ebms.orchestrator.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that ebms-orchestrator/src/main/resources/application.yml:
 *   (a) Parses as valid YAML (via Spring's YamlPropertiesFactoryBean → SnakeYAML)
 *   (b) The `spring.jpa.properties.hibernate.default_schema` property is
 *       fully driven by the SPRING_JPA_DEFAULT_SCHEMA env/system property
 *       with empty-string default when unset (i.e. no hardcoded 'public').
 *
 * This directly validates the fix that removes the hardcoded schema override.
 */
class ApplicationYamlSchemaPropertyTest {

    private static final String YAML_KEY = "spring.jpa.properties.hibernate.default_schema";
    private static final String ENV_KEY  = "SPRING_JPA_DEFAULT_SCHEMA";

    private Properties loadRawYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();
        assertNotNull(props, "YAML must parse to non-null Properties");
        return props;
    }

    @Test
    void yamlParsesSuccessfully() {
        Properties props = loadRawYaml();
        // Presence of a known key confirms parse
        assertEquals("ebms-orchestrator", props.getProperty("spring.application.name"));
        assertEquals("validate", props.getProperty("spring.jpa.hibernate.ddl-auto"));
    }

    @Test
    void defaultSchemaPlaceholderIsPresentAndNotHardcoded() {
        Properties props = loadRawYaml();
        String raw = props.getProperty(YAML_KEY);
        // Raw YAML value (before placeholder resolution) is the placeholder literal
        assertNotNull(raw, "default_schema key must exist in yaml");
        assertEquals("${" + ENV_KEY + ":}", raw,
                "default_schema must be a placeholder with empty default, not a hardcoded value like 'public'");
        assertFalse(raw.contains("public"), "must not contain hardcoded 'public'");
    }

    @Test
    void defaultSchemaResolvesToEmptyWhenEnvUnset() {
        Properties props = loadRawYaml();
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new PropertiesPropertySource("yamlUnderTest", props));
        // Ensure env is NOT set
        assertNull(System.getenv(ENV_KEY), "test assumes env var not preset in sandbox");
        String resolved = env.getProperty(YAML_KEY);
        assertNotNull(resolved);
        assertEquals("", resolved, "must resolve to empty string when SPRING_JPA_DEFAULT_SCHEMA is unset");
    }

    @Test
    void defaultSchemaResolvesToOverrideWhenSystemPropertySet() {
        Properties props = loadRawYaml();
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        // Simulate an override via a higher-priority property source
        Properties override = new Properties();
        override.setProperty(ENV_KEY, "orchestrator");
        sources.addFirst(new PropertiesPropertySource("override", override));
        sources.addLast(new PropertiesPropertySource("yamlUnderTest", props));
        String resolved = env.getProperty(YAML_KEY);
        assertEquals("orchestrator", resolved,
                "must resolve to the override value when SPRING_JPA_DEFAULT_SCHEMA is provided");
    }
}
