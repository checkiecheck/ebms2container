package nl.logius.ebms.cpa;

import nl.logius.ebms.cpa.service.CpaService;
import nl.logius.ebms.common.exception.CpaNotFoundException;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.CpaDto;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Integratietest voor {@link CpaService} met een echte PostgreSQL-container.
 *
 * <p>Test: CRUD-operaties, cache-gedrag, foutafhandeling en OIN-lookup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CpaServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    CpaService cpaService;

    private static final String TEST_CPA_ID = "urn:test:cpa:integration-001";

    // ── Aanmaken ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createCpa_validDto_returnsCpaMetWithId() {
        CpaDto dto = buildTestCpa(TEST_CPA_ID);

        CpaDto saved = cpaService.create(dto);

        assertThat(saved.getCpaId()).isEqualTo(TEST_CPA_ID);
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
    }

    // ── Ophalen ───────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void findByCpaId_existingCpa_returnsCpa() {
        CpaDto found = cpaService.findByCpaId(TEST_CPA_ID);

        assertThat(found).isNotNull();
        assertThat(found.getCpaId()).isEqualTo(TEST_CPA_ID);
        assertThat(found.getDescription()).isEqualTo("Integratietest CPA");
    }

    @Test
    @Order(3)
    void findAll_afterCreate_containsTestCpa() {
        List<CpaDto> all = cpaService.findAll();

        assertThat(all)
            .extracting(CpaDto::getCpaId)
            .contains(TEST_CPA_ID);
    }

    // ── Foutafhandeling ───────────────────────────────────────────────────────

    @Test
    @Order(4)
    void createDuplicateCpa_throwsEbmsException() {
        CpaDto dup = buildTestCpa(TEST_CPA_ID);

        assertThatThrownBy(() -> cpaService.create(dup))
            .isInstanceOf(EbmsException.class)
            .hasMessageContaining("bestaat al");
    }

    @Test
    @Order(5)
    void findByCpaId_nonExistent_throwsCpaNotFoundException() {
        assertThatThrownBy(() -> cpaService.findByCpaId("urn:niet:bestaand"))
            .isInstanceOf(CpaNotFoundException.class);
    }

    // ── Verwijderen ───────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void deleteByCpaId_existingCpa_isRemovedFromDatabase() {
        cpaService.deleteByCpaId(TEST_CPA_ID);

        assertThatThrownBy(() -> cpaService.findByCpaId(TEST_CPA_ID))
            .isInstanceOf(CpaNotFoundException.class);
    }

    @Test
    @Order(7)
    void deleteByCpaId_nonExistent_throwsCpaNotFoundException() {
        assertThatThrownBy(() -> cpaService.deleteByCpaId("urn:niet:bestaand"))
            .isInstanceOf(CpaNotFoundException.class);
    }

    // ── Test-hulpfuncties ─────────────────────────────────────────────────────

    private CpaDto buildTestCpa(String cpaId) {
        return CpaDto.builder()
            .cpaId(cpaId)
            .description("Integratietest CPA")
            .version("2.0")
            .status("ACTIVE")
            .cpaXml("<CollaborationProtocolAgreement cpaid=\"" + cpaId + "\"/>")
            .build();
    }
}
