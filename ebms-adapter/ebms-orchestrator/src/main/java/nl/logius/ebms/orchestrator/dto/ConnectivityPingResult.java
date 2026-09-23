package nl.logius.ebms.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectivityPingResult(
    boolean success,
    Integer httpStatus,
    long latencyMs,
    String message,
    String errorCode
) {

    public static ConnectivityPingResult success(long latencyMs) {
        return new ConnectivityPingResult(true, 200, latencyMs, "Pong ontvangen", null);
    }

    public static ConnectivityPingResult failure(long latencyMs, String message, String errorCode) {
        return new ConnectivityPingResult(false, null, latencyMs, message, errorCode);
    }
}