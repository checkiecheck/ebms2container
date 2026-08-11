package nl.logius.ebms.cpa.controller;

import lombok.extern.slf4j.Slf4j;
import nl.logius.ebms.common.exception.CpaNotFoundException;
import nl.logius.ebms.common.exception.EbmsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Globale exception-afhandeling voor de cpa-service.
 * Geeft RFC 9457 ProblemDetail responses terug.
 */
@RestControllerAdvice
@Slf4j
public class CpaErrorHandler {

    @ExceptionHandler(CpaNotFoundException.class)
    public ProblemDetail handleNotFound(CpaNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(URI.create("urn:nl:logius:ebms:error:cpa-not-found"));
        pd.setTitle("CPA niet gevonden");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(EbmsException.class)
    public ProblemDetail handleEbmsException(EbmsException ex) {
        boolean isConflict = ex.getErrorCode().contains("ALREADY_EXISTS");
        ProblemDetail pd = ProblemDetail.forStatus(
            isConflict ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST);
        pd.setType(URI.create("urn:nl:logius:ebms:error:" + ex.getErrorCode().toLowerCase()));
        pd.setTitle(ex.getErrorCode());
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(URI.create("urn:nl:logius:ebms:error:validation-failed"));
        pd.setTitle("Validatiefout");
        pd.setDetail(ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validatiefout"));
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex) {
        log.error("Onverwachte fout in cpa-service", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setType(URI.create("urn:nl:logius:ebms:error:internal"));
        pd.setTitle("Interne serverfout");
        pd.setDetail("Er is een onverwachte fout opgetreden. Raadpleeg de logs.");
        return pd;
    }
}
