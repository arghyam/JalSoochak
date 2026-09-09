package org.arghyam.jalsoochak.telemetry.dto.requests;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 255-character cap on {@code issueReason}, closing the length half of a CWE-20 audit finding.
 *
 * <p>{@code IssueReportRequest} carries only standard constraints, so unlike
 * {@link AssamReadingRequestValidationTest} the default validator factory is enough — no custom
 * constraint needs constructor wiring here.
 */
class IssueReportRequestValidationTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    private static Set<ConstraintViolation<IssueReportRequest>> validate(String issueReason) {
        return VALIDATOR.validate(IssueReportRequest.builder()
                .contactId("12345")
                .issueReason(issueReason)
                .build());
    }

    @Test
    void aReasonAtExactlyTheCapIsValid() {
        assertEquals(0, validate("a".repeat(IssueReportRequest.MAX_ISSUE_REASON_LENGTH)).size());
    }

    @Test
    void oneCharacterOverTheCapIsRejected() {
        Set<ConstraintViolation<IssueReportRequest>> violations =
                validate("a".repeat(IssueReportRequest.MAX_ISSUE_REASON_LENGTH + 1));

        assertEquals(1, violations.size());
        assertTrue(violations.iterator().next().getMessage().contains("must not exceed 255 characters"));
    }

    @Test
    void theAuditedTenThousandCharacterReasonIsRejected() {
        assertEquals(1, validate("a".repeat(10_000)).size());
    }

    @Test
    void aNullReasonProducesNoViolationSoTheLocalisedRequiredMessageStillOwnsThatCase() {
        // @Size ignores null by design. If this ever starts failing, a missing reason has silently
        // become a 400 instead of the localised "Issue reason is required." reply.
        assertEquals(0, validate(null).size());
    }

    @Test
    void aBlankReasonProducesNoViolationForTheSameReason() {
        assertEquals(0, validate("   ").size());
    }

    @Test
    void theCapCountsCharactersNotBytesSoAFullLengthDevanagariReasonIsValid() {
        // "पानी" is 4 characters but 12 bytes in UTF-8; a byte-based cap would reject this at
        // roughly a third of the advertised limit, silently penalising non-English reports.
        int max = IssueReportRequest.MAX_ISSUE_REASON_LENGTH;
        String devanagari = "पानी".repeat(max / 4) + "पानी".substring(0, max % 4);

        assertEquals(max, devanagari.length());
        assertTrue(devanagari.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > max);
        assertEquals(0, validate(devanagari).size());
    }
}
