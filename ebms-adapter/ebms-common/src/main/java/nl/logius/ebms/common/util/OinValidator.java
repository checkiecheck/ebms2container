package nl.logius.ebms.common.util;

import java.util.regex.Pattern;

/**
 * Valideert OIN-nummers conform ISO 6523 (Organisatie Identificatie Nummer).
 *
 * <p>OIN-regels:
 * <ul>
 *   <li>Exact 20 cijfers</li>
 *   <li>Begint met {@code 00} (Nederlandse ICD-code)</li>
 *   <li>Daarna 18 cijfers (organisatie-specificiek)</li>
 * </ul>
 *
 * <p>Voorbeeld: {@code 00000000000000000000}
 */
public final class OinValidator {

    /** OIN-patroon: '00' gevolgd door 18 cijfers = 20 cijfers totaal. */
    private static final Pattern OIN_PATTERN = Pattern.compile("^00\\d{18}$");

    private OinValidator() {
        // Utility klasse – niet instantiëren
    }

    /** Geeft true terug als het OIN syntactisch geldig is. */
    public static boolean isValid(String oin) {
        return oin != null && OIN_PATTERN.matcher(oin).matches();
    }

    /**
     * Valideert het OIN en gooit een {@link IllegalArgumentException} als het ongeldig is.
     *
     * @param oin het te valideren OIN
     * @throws IllegalArgumentException als het OIN niet voldoet aan de ISO 6523 regels
     */
    public static void validate(String oin) {
        if (!isValid(oin)) {
            throw new IllegalArgumentException(
                "Ongeldig OIN: [" + oin + "]. " +
                "OIN moet exact 20 cijfers zijn en beginnen met '00' (ISO 6523 NL)."
            );
        }
    }

    /**
     * Extraheert en valideert het OIN uit een X-Forwarded-Client-OIN header-waarde.
     * De header kan extra witruimte bevatten die gestript wordt.
     *
     * @param headerValue de ruwe header-waarde
     * @return getrimd OIN als het geldig is
     * @throws IllegalArgumentException als het OIN ongeldig is
     */
    public static String extractFromHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new IllegalArgumentException("X-Forwarded-Client-OIN header is leeg of afwezig");
        }
        String oin = headerValue.strip();
        validate(oin);
        return oin;
    }
}
