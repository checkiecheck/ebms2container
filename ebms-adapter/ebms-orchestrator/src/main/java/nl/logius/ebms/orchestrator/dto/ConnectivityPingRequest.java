package nl.logius.ebms.orchestrator.dto;

import jakarta.validation.constraints.NotBlank;

public record ConnectivityPingRequest(
    @NotBlank String fromPartyId
) {}