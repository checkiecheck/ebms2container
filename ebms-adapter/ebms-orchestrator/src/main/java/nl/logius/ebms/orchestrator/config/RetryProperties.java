package nl.logius.ebms.orchestrator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuratie-properties voor Reliable Messaging (ebMS2 retry-mechanisme).
 *
 * <p>Prefix: {@code ebms.reliable-messaging}
 *
 * <p>Voorbeeld {@code application.yml}:
 * <pre>
 * ebms:
 *   reliable-messaging:
 *     max-retries: 3
 *     retry-interval-seconds: 60
 *     retry-check-interval-ms: 300000
 *     duplicate-window-hours: 24
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "ebms.reliable-messaging")
@Getter
@Setter
public class RetryProperties {

    /** Maximaal aantal retrypogingen per bericht (rm-profielen). */
    private int maxRetries = 3;

    /**
     * Minimaal interval in seconden tussen retrypogingen per bericht.
     * Berichten met {@code lastRetryAt &lt; now - retryIntervalSeconds} komen in aanmerking.
     */
    private int retryIntervalSeconds = 60;

    /**
     * Interval in milliseconden waarmee de retry-scheduler draait.
     * Standaard: 5 minuten (300.000 ms).
     */
    private int retryCheckIntervalMs = 300_000;

    /**
     * Tijdvenster in uren voor duplicate-suppression.
     * Berichten buiten dit venster worden niet meer als duplicaat beschouwd.
     */
    private int duplicateWindowHours = 24;
}
