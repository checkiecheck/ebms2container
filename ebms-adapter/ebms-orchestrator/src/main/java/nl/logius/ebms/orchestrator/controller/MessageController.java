package nl.logius.ebms.orchestrator.controller;

import lombok.RequiredArgsConstructor;
import nl.logius.ebms.orchestrator.dto.MessageDto;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
     * Voorbeeld: {@code GET /api/admin/messages?page=0&size=50}
     */
    @GetMapping
    public Page<MessageDto> getMessages(
            @PageableDefault(size = 50, sort = "timestamp", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return messageRepository.findAll(pageable).map(MessageDto::from);
    }
}
