package nl.logius.ebms.orchestrator.service;

import nl.logius.ebms.common.model.cpa.CpaDto;
import lombok.Getter;

/**
 * Resultaat van een CPA + OIN-validatie door de {@link CpaValidationService}.
 */
@Getter
public final class CpaValidationResult {

    private final boolean valid;
    private final String  errorMessage;
    private final CpaDto  cpa;

    /**
     * True als de cpa-service niet bereikbaar was (fail-open scenario).
     * In productie moet dit fail-closed zijn.
     */
    private final boolean serviceUnavailable;

    private CpaValidationResult(boolean valid, String errorMessage,
                                 CpaDto cpa, boolean serviceUnavailable) {
        this.valid              = valid;
        this.errorMessage       = errorMessage;
        this.cpa                = cpa;
        this.serviceUnavailable = serviceUnavailable;
    }

    public static CpaValidationResult success(CpaDto cpa) {
        return new CpaValidationResult(true, null, cpa, false);
    }

    public static CpaValidationResult failure(String errorMessage) {
        return new CpaValidationResult(false, errorMessage, null, false);
    }

    public static CpaValidationResult serviceUnavailable(String errorMessage) {
        return new CpaValidationResult(false,
            "cpa-service niet bereikbaar: " + errorMessage, null, true);
    }
}
