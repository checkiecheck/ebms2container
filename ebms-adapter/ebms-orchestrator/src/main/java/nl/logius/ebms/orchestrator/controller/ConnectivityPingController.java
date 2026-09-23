package nl.logius.ebms.orchestrator.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import nl.logius.ebms.orchestrator.dto.ConnectivityPingRequest;
import nl.logius.ebms.orchestrator.dto.ConnectivityPingResult;
import nl.logius.ebms.orchestrator.service.ConnectivityPingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/cpa")
@RequiredArgsConstructor
public class ConnectivityPingController {

    private final ConnectivityPingService connectivityPingService;

    @PostMapping("/{cpaId}/partners/{partyId}/ping")
    public ResponseEntity<ConnectivityPingResult> ping(
            @PathVariable String cpaId,
            @PathVariable String partyId,
            @Valid @RequestBody ConnectivityPingRequest request) {
        ConnectivityPingResult result = connectivityPingService.ping(
            cpaId, request.fromPartyId(), partyId);
        return ResponseEntity.status(result.success() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY)
            .body(result);
    }
}