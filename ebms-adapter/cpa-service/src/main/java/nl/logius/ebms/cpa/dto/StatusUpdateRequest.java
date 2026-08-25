package nl.logius.ebms.cpa.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Request-body voor {@code PATCH /api/cpa/{cpaId}/status} - wijzigt uitsluitend de status
 * van een bestaande CPA (bijv. de "Status toggle" in het admin-dashboard).
 */
@Getter
@Setter
public class StatusUpdateRequest {

    private String status;
}
