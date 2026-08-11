package nl.logius.ebms.cpa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * cpa-service – Collaboration Protocol Agreement beheer.
 *
 * <p>Verantwoordelijkheden:
 * <ul>
 *   <li>REST API voor CPA-opslag, -ophalen en -validatie</li>
 *   <li>Partner-certificaten (PKI) koppelen aan CPA's</li>
 *   <li>OIN-lookup via ISO 6523-koppeling</li>
 *   <li>Read-heavy caching via Caffeine (L1 in-process)</li>
 * </ul>
 *
 * <p>Poort: 8081
 */
@SpringBootApplication
@EnableCaching
public class CpaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CpaServiceApplication.class, args);
    }
}
