package nl.logius.ebms.orchestrator.service;

import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Gecachede CPA-kanaal lookup voor de outbound AMQP-pipeline.
 *
 * <p>Aparte bean zodat Spring AOP de {@link Cacheable}-interceptor correct kan toepassen.
 * (Self-invocation binnen dezelfde bean wordt niet door de AOP-proxy onderschept.)
 *
 * <p>Cache-strategie: Caffeine in-memory, geconfigureerd via {@code spring.cache.caffeine.spec}.
 * De TTL is configureerbaar via {@code ebms.cache.cpa-channel-ttl-minutes}.
 */
@Service
@Slf4j
public class CpaChannelCacheService {

    private final CpaValidationService cpaValidationService;

    public CpaChannelCacheService(CpaValidationService cpaValidationService) {
        this.cpaValidationService = cpaValidationService;
    }

    /**
     * Haalt het afleverkanaal op voor een CPA-partij via de cpa-service.
     *
     * <p>Gecached via Spring Caffeine: vermijdt trage HTTP-calls naar de CPA-database
     * bij hoge berichtvolumes (conform Logius/BIO-eis voor lage latency).
     *
     * @param cpaId     de CPA-identifier
     * @param toPartyId partij-ID van de ontvanger
     * @return gecached {@link DeliveryChannelDto}
     */
    @Cacheable(value = "outbound-channel", key = "#cpaId + ':' + #toPartyId")
    public DeliveryChannelDto getChannel(String cpaId, String toPartyId) {
        log.debug("[CACHE MISS] Afleverkanaal opzoeken: cpaId={} toPartyId={}", cpaId, toPartyId);
        return cpaValidationService.getDeliveryChannel(cpaId, toPartyId);
    }
}
