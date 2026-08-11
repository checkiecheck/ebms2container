package nl.logius.ebms.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ebms-orchestrator – hoofd Spring Boot applicatie.
 *
 * <p>Verantwoordelijkheden:
 * <ul>
 *   <li>SOAP 1.1 endpoint voor binnenkomende ebMS2-berichten (Apache CXF)</li>
 *   <li>Bericht-state beheer in PostgreSQL (Reliable Messaging / duplicate suppression)</li>
 *   <li>CPA-validatie via cpa-service (HTTP intern)</li>
 *   <li>Crypto-delegatie via crypto-service (HTTP intern)</li>
 *   <li>Asynchrone verwerking via RabbitMQ (AMQP)</li>
 *   <li>Ping / Echo services conform ISO 15000-2</li>
 * </ul>
 *
 * <p>Poort: 8080 | SOAP-context: /services
 */
@SpringBootApplication
@EnableScheduling
public class EbmsOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EbmsOrchestratorApplication.class, args);
    }
}
