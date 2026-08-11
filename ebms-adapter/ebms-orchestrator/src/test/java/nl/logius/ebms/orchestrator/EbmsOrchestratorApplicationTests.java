package nl.logius.ebms.orchestrator;

import org.junit.jupiter.api.Test;

/**
 * Smoke-test voor de ebms-orchestrator.
 * Spring-contexttests volgen in Fase 2 na implementatie van
 * controllers, services en repositories.
 */
class EbmsOrchestratorApplicationTests {

    @Test
    void applicationClassExists() {
        // Bevestigt dat de hoofdklasse aanwezig en compileerbaar is
        var appClass = EbmsOrchestratorApplication.class;
        assert appClass != null;
    }
}
