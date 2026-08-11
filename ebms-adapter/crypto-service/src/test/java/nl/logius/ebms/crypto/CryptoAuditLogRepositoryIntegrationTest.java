package nl.logius.ebms.crypto;

import nl.logius.ebms.crypto.entity.CryptoAuditLogEntity;
import nl.logius.ebms.crypto.repository.CryptoAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integratietest voor de crypto-service met een echte PostgreSQL-container.
 *
 * <p>Getest: opslaan en terugzoeken van {@code CryptoAuditLogEntity} vermeldingen.
 * De {@code KeyStoreService} werkt correct zonder KeyStore-bestand
 * (lege KeyStore wordt in geheugen aangemaakt bij ontbrekend bestand).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class CryptoAuditLogRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Gebruik een pad dat niet bestaat → KeyStoreService maakt lege KS aan
        registry.add("ebms.crypto.keystore.path",     () -> "/tmp/test-keystore-nonexistent.p12");
        registry.add("ebms.crypto.keystore.password", () -> "testpass");
    }

    @Autowired
    CryptoAuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
    }

    // ── Opslaan ───────────────────────────────────────────────────────────────

    @Test
    void saveAuditLog_successOperation_idAssignedAndPersisted() {
        CryptoAuditLogEntity log = CryptoAuditLogEntity.builder()
            .operation("XML_SIGN")
            .keyAlias("test-signing-key")
            .messageId("msg-sign-001")
            .result("SUCCESS")
            .durationMs(12)
            .build();

        CryptoAuditLogEntity saved = auditLogRepository.save(log);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOperation()).isEqualTo("XML_SIGN");
        assertThat(saved.getResult()).isEqualTo("SUCCESS");
        assertThat(saved.getPerformedAt()).isNotNull();
    }

    @Test
    void saveAuditLog_failureOperation_errorDetailPersisted() {
        CryptoAuditLogEntity log = CryptoAuditLogEntity.builder()
            .operation("XML_ENCRYPT")
            .keyAlias("ontvanger-key")
            .messageId("msg-enc-fail-001")
            .result("FAILURE")
            .errorCode("SecurityFailure")
            .errorDetail("Certificaat verlopen")
            .durationMs(5)
            .build();

        CryptoAuditLogEntity saved = auditLogRepository.save(log);

        assertThat(saved.getResult()).isEqualTo("FAILURE");
        assertThat(saved.getErrorCode()).isEqualTo("SecurityFailure");
        assertThat(saved.getErrorDetail()).contains("verlopen");
    }

    // ── Terugzoeken ───────────────────────────────────────────────────────────

    @Test
    void findAll_afterMultipleSaves_returnsAllLogs() {
        auditLogRepository.save(buildLog("XML_SIGN",    "SUCCESS"));
        auditLogRepository.save(buildLog("XML_VERIFY",  "SUCCESS"));
        auditLogRepository.save(buildLog("XML_ENCRYPT", "FAILURE"));

        List<CryptoAuditLogEntity> all = auditLogRepository.findAll();

        assertThat(all).hasSize(3);
        assertThat(all)
            .extracting(CryptoAuditLogEntity::getOperation)
            .containsExactlyInAnyOrder("XML_SIGN", "XML_VERIFY", "XML_ENCRYPT");
    }

    @Test
    void findAll_emptyRepository_returnsEmptyList() {
        assertThat(auditLogRepository.findAll()).isEmpty();
    }

    @Test
    void countLogs_multipleEntries_returnsCorrectCount() {
        auditLogRepository.save(buildLog("XML_SIGN", "SUCCESS"));
        auditLogRepository.save(buildLog("XML_SIGN", "SUCCESS"));

        assertThat(auditLogRepository.count()).isEqualTo(2L);
    }

    // ── Test-hulpfuncties ─────────────────────────────────────────────────────

    private CryptoAuditLogEntity buildLog(String operation, String result) {
        return CryptoAuditLogEntity.builder()
            .operation(operation)
            .keyAlias("test-key")
            .messageId("msg-" + System.nanoTime())
            .result(result)
            .durationMs(10)
            .build();
    }
}
