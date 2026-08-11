package nl.logius.ebms.orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring {@link RestClient}-configuratie voor interne service-aanroepen.
 *
 * <p>De RestClient is beschikbaar in Spring 6.1+ (Spring Boot 3.2+) en biedt
 * een vloeiende API voor synchrone HTTP-aanroepen zonder WebFlux-afhankelijkheid.
 */
@Configuration
public class RestClientConfig {

    @Value("${ebms.cpa-service-url}")
    private String cpaServiceUrl;

    /**
     * RestClient geconfigureerd voor aanroepen naar de cpa-service.
     * Base-URL: configureerbaar via {@code ebms.cpa-service-url}.
     */
    @Bean("cpaRestClient")
    public RestClient cpaRestClient() {
        return RestClient.builder()
            .baseUrl(cpaServiceUrl)
            .defaultHeader("Accept",       "application/json")
            .defaultHeader("Content-Type", "application/json")
            .build();
    }
}
