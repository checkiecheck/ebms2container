package nl.logius.ebms.cpa.service;

import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.cpa.entity.CpaEntity;
import nl.logius.ebms.cpa.mapper.CpaMapper;
import nl.logius.ebms.cpa.repository.CpaDeliveryChannelRepository;
import nl.logius.ebms.cpa.repository.CpaPartyRepository;
import nl.logius.ebms.cpa.repository.CpaRepository;
import nl.logius.ebms.cpa.repository.PartnerCertificateRepository;
import nl.logius.ebms.cpa.util.CpaPartyXmlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Spring-context slice test that PROVES the @CacheEvict SpEL fix on CpaService.update().
 *
 * <p>Before fix: @CacheEvict(key = "#cpaIdFromXml") on update() referenced a LOCAL variable
 * (not a method parameter), so Spring's CacheAspectSupport.generateKey() returned null and
 * threw IllegalArgumentException AFTER the DB save.
 *
 * <p>After fix: @CacheEvict(key = "#result.cpaId") references the returned CpaDto's cpaId
 * (default beforeInvocation=false), so SpEL resolves correctly and the right cache entry
 * is evicted.
 */
@SpringJUnitConfig
@Import(CpaServiceUpdateCacheEvictTest.CachingTestConfig.class)
class CpaServiceUpdateCacheEvictTest {

    private static final String CPA_ID = "CPAID_EchoService-1-0-HTTPS";

    @TestConfiguration
    @EnableCaching
    static class CachingTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("cpa-by-id", "channel-by-party");
        }

        @Bean
        CpaService cpaService(CpaRepository r1, CpaPartyRepository r2,
                              CpaDeliveryChannelRepository r3, PartnerCertificateRepository r4,
                              CpaMapper mapper, CpaPartyXmlParser parser) {
            return new CpaService(r1, r2, r3, r4, mapper, parser);
        }
    }

    @MockBean CpaRepository cpaRepository;
    @MockBean CpaPartyRepository partyRepository;
    @MockBean CpaDeliveryChannelRepository channelRepository;
    @MockBean PartnerCertificateRepository certRepository;
    @MockBean CpaMapper cpaMapper;
    @MockBean CpaPartyXmlParser partyXmlParser;

    @Autowired CpaService cpaService;
    @Autowired CacheManager cacheManager;

    private CpaEntity existing;

    @BeforeEach
    void setUp() {
        Cache cache = cacheManager.getCache("cpa-by-id");
        if (cache != null) cache.clear();

        existing = CpaEntity.builder()
            .id(UUID.randomUUID())
            .cpaId(CPA_ID)
            .version("1.0")
            .description("orig")
            .status("ACTIVE")
            .cpaXml("<orig/>")
            .createdAt(Instant.parse("2025-01-01T00:00:00Z"))
            .updatedAt(Instant.parse("2025-01-01T00:00:00Z"))
            .parties(new ArrayList<>())
            .build();

        lenient().when(partyXmlParser.parseCpaId(any())).thenReturn(CPA_ID);
        lenient().when(partyXmlParser.parseParties(any())).thenReturn(new ArrayList<>());
        lenient().when(partyXmlParser.parseStartDate(any())).thenReturn(null);
        lenient().when(partyXmlParser.parseEndDate(any())).thenReturn(null);
        lenient().when(partyXmlParser.parseCertificates(any(), any())).thenReturn(new ArrayList<>());
        lenient().when(certRepository.findByCpaId(any())).thenReturn(new ArrayList<>());

        when(cpaRepository.findByCpaId(CPA_ID)).thenReturn(Optional.of(existing));
        when(cpaRepository.save(any(CpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cpaMapper.toDto(any(CpaEntity.class))).thenAnswer(inv -> {
            CpaEntity e = inv.getArgument(0);
            return CpaDto.builder()
                .cpaId(e.getCpaId())
                .version(e.getVersion())
                .description(e.getDescription())
                .status(e.getStatus())
                .cpaXml(e.getCpaXml())
                .createdAt(e.getCreatedAt())
                .build();
        });
    }

    /**
     * Regression for the reported IllegalArgumentException: "Null key returned for cache
     * operation ... key='#cpaIdFromXml'". If the fix regressed, this test would throw
     * that exact exception because the SpEL still references an unresolvable variable.
     */
    @Test
    void update_doesNotThrowIllegalArgumentException_onCacheKeyResolution() {
        CpaDto in = CpaDto.builder()
            .cpaId(CPA_ID)
            .description("updated")
            .cpaXml("<new xmlns:tp='http://example.com/tp'/>")
            .build();

        assertThatCode(() -> cpaService.update(CPA_ID, in))
            .doesNotThrowAnyException();
    }

    /**
     * Proves the @CacheEvict actually evicts the correct entry (i.e. SpEL '#result.cpaId'
     * resolves to CPA_ID and hits the right cache key).
     */
    @Test
    void update_evictsCacheEntryForUpdatedCpaId() {
        Cache cache = cacheManager.getCache("cpa-by-id");
        assertThat(cache).isNotNull();

        // Simulate a prior GET populating the cache
        cache.put(CPA_ID, CpaDto.builder().cpaId(CPA_ID).description("stale-cached").build());
        assertThat(cache.get(CPA_ID)).isNotNull();
        assertThat(cache.get(CPA_ID).get()).isInstanceOf(CpaDto.class);

        CpaDto in = CpaDto.builder()
            .cpaId(CPA_ID)
            .description("fresh-updated")
            .cpaXml("<new/>")
            .build();

        CpaDto result = cpaService.update(CPA_ID, in);

        // Verify the update actually returned the fresh data
        assertThat(result.getCpaId()).isEqualTo(CPA_ID);
        assertThat(result.getDescription()).isEqualTo("fresh-updated");

        // Verify the cache entry for CPA_ID was evicted (no more stale data)
        assertThat(cache.get(CPA_ID))
            .as("cache entry for cpaId '%s' should have been evicted by @CacheEvict(key='#result.cpaId')", CPA_ID)
            .isNull();
    }
}
