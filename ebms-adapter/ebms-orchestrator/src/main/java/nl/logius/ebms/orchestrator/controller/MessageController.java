package nl.logius.ebms.orchestrator.controller;

import lombok.RequiredArgsConstructor;
import nl.logius.ebms.orchestrator.dto.MessageDto;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST API voor de admin message-monitor (single-page dashboard onder
 * {@code /admin/index.html}).
 */
@RestController
@RequestMapping("/api/admin/messages")
@RequiredArgsConstructor
public class MessageController {

    private final EbmsMessageRepository messageRepository;

    /**
     * Geeft de verwerkte berichten terug, gepagineerd en gesorteerd op timestamp (nieuwste eerst).
     * Toont standaard ALLE berichten (INBOUND + OUTBOUND) — optioneel te filteren op richting.
     * Voorbeeld: {@code GET /api/admin/messages?page=0&size=50}
     * Voorbeeld met filter: {@code GET /api/admin/messages?direction=INBOUND}
     */
    @GetMapping
    public Page<MessageDto> getMessages(
            @PageableDefault(size = 50, sort = "timestamp", direction = Sort.Direction.DESC)
            Pageable pageable,
            @RequestParam(required = false) MessageDirection direction) {
        Page<EbmsMessageEntity> page = direction != null
            ? messageRepository.findByDirection(direction, pageable)
            : messageRepository.findAll(pageable);
        return page.map(MessageDto::from);
    }
}
