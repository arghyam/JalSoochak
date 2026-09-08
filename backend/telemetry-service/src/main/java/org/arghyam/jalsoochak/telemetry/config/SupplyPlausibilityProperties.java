package org.arghyam.jalsoochak.telemetry.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * SUPPLY-PLAUSIBILITY: rollout mode and thresholds for the implausible-daily-supply check.
 *
 * <p>Governs <strong>both</strong> volume checks on the reading path — the new quarantine gate and
 * the {@code OVER_WATER_SUPPLY} check whose units were corrected alongside it. The second went from
 * effectively never firing to firing often, so the two must be observed and enforced together or a
 * deploy starts rejecting field submissions en masse.
 *
 * <p>Mode follows {@code telemetry.webhook.auth.mode}: the kill switch is a restart, not a deploy.
 * The default is {@code AUDIT} rather than {@code ENFORCE} — the inverse of the webhook filter's
 * default, and deliberately so. That filter protects endpoints that must not ship unprotected by
 * omission; this one drops real field submissions on an unvalidated threshold, and a quarantined
 * reading also marks its operator absent for the day. Shipping straight to {@code ENFORCE} would
 * surface as a daily-report discrepancy long before anyone traced it back here.
 *
 * <p>Values are bound as strings and parsed here rather than bound as numbers, so a bad setting
 * fails startup with a message naming the property instead of a binding stack trace — and so
 * "unparseable" can be told apart from "deliberately unset". In a rolling deploy a failed start
 * leaves the previous container serving, which is the safe outcome.
 */
@Component
@ConfigurationProperties(prefix = "telemetry.supply-plausibility")
public class SupplyPlausibilityProperties {

    private static final Logger log = LoggerFactory.getLogger(SupplyPlausibilityProperties.class);

    /** ~2.7x the 55 LPCD design norm. A policy number, not one derived from production data. */
    private static final String DEFAULT_LIMIT_PER_PERSON_LITRES = "150";

    private static final String DEFAULT_MEMBERS_PER_HOUSEHOLD = "5";

    public enum Mode {
        /** Quarantine the reading and reject the submission. */
        ENFORCE,
        /** Evaluate and record what would have been quarantined, but serve normally. */
        AUDIT,
        /** Skip the check entirely; the policy is never reached. */
        OFF
    }

    private String mode = Mode.AUDIT.name();
    private String limitPerPersonLitres = DEFAULT_LIMIT_PER_PERSON_LITRES;
    private String defaultMembersPerHousehold = DEFAULT_MEMBERS_PER_HOUSEHOLD;

    private Mode resolvedMode = Mode.AUDIT;
    private BigDecimal resolvedLimitPerPersonLitres = new BigDecimal(DEFAULT_LIMIT_PER_PERSON_LITRES);
    private BigDecimal resolvedDefaultMembersPerHousehold = new BigDecimal(DEFAULT_MEMBERS_PER_HOUSEHOLD);

    @PostConstruct
    public void init() {
        this.resolvedMode = parseMode(mode);

        // Required, unlike the household default below: a blank limit would leave the policy with no
        // ceiling and silently skip every reading, which is mode=OFF wearing a disguise. If the check
        // is meant to be off, say so in the mode.
        this.resolvedLimitPerPersonLitres = parsePositiveDecimal(
                limitPerPersonLitres, "telemetry.supply-plausibility.limit-per-person-litres", true);

        // Optional: blank means "no fallback", so a tenant whose AVERAGE_MEMBERS_PER_HOUSEHOLD is
        // missing or unparseable is skipped rather than assessed against a number nobody chose.
        this.resolvedDefaultMembersPerHousehold = parsePositiveDecimal(
                defaultMembersPerHousehold,
                "telemetry.supply-plausibility.default-members-per-household", false);

        log.info("Supply plausibility check initialised: mode={} limitPerPersonLitres={} "
                        + "defaultMembersPerHousehold={}",
                resolvedMode, resolvedLimitPerPersonLitres, resolvedDefaultMembersPerHousehold);
    }

    private static Mode parseMode(String raw) {
        String candidate = raw == null ? "" : raw.trim();
        if (candidate.isEmpty()) {
            throw new IllegalStateException("telemetry.supply-plausibility.mode must not be blank");
        }
        try {
            return Mode.valueOf(candidate.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Unknown telemetry.supply-plausibility.mode '" + candidate
                            + "'. Expected one of ENFORCE, AUDIT, OFF.", e);
        }
    }

    private static BigDecimal parsePositiveDecimal(String raw, String property, boolean required) {
        String candidate = raw == null ? "" : raw.trim();
        if (candidate.isEmpty()) {
            if (required) {
                throw new IllegalStateException(property + " must not be blank");
            }
            return null;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(candidate);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    property + " is not a number: '" + candidate + "'", e);
        }
        if (parsed.signum() <= 0) {
            throw new IllegalStateException(
                    property + " must be greater than zero, but was " + parsed.toPlainString());
        }
        return parsed;
    }

    public Mode getResolvedMode() {
        return resolvedMode;
    }

    /** @return litres per person per day a scheme may supply. Never null. */
    public BigDecimal getResolvedLimitPerPersonLitres() {
        return resolvedLimitPerPersonLitres;
    }

    /**
     * @return the fallback household size for tenants with no usable
     *         {@code AVERAGE_MEMBERS_PER_HOUSEHOLD}, or {@code null} when no fallback is configured
     */
    public BigDecimal getResolvedDefaultMembersPerHousehold() {
        return resolvedDefaultMembersPerHousehold;
    }

    /** @return true when a failing reading should actually be quarantined and rejected. */
    public boolean isEnforcing() {
        return resolvedMode == Mode.ENFORCE;
    }

    /** @return true when the check should not run at all. */
    public boolean isDisabled() {
        return resolvedMode == Mode.OFF;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getLimitPerPersonLitres() {
        return limitPerPersonLitres;
    }

    public void setLimitPerPersonLitres(String limitPerPersonLitres) {
        this.limitPerPersonLitres = limitPerPersonLitres;
    }

    public String getDefaultMembersPerHousehold() {
        return defaultMembersPerHousehold;
    }

    public void setDefaultMembersPerHousehold(String defaultMembersPerHousehold) {
        this.defaultMembersPerHousehold = defaultMembersPerHousehold;
    }
}
