package org.arghyam.jalsoochak.telemetry.channel;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A unit a reading can be submitted in, named by its UCUM code and tied to the one channel and
 * {@linkplain MeterRegister register} it measures. A unit may also accept common non-UCUM spellings,
 * such as {@code kWh} or {@code litre}; only {@link #code()} is ever stored.
 *
 * <p>{@code flow_reading_table.confirmed_reading} holds a value in its {@link #storedUnit()}: the
 * channel's {@linkplain ReadingChannel#standardUnit() standard unit}, because queries do arithmetic on
 * that column directly, except a kVAh reading, which is stored in kVAh because only the pumps' power
 * factor turns it into kWh. {@code flow_reading_table.submitted_unit} stores {@link #code()} of the
 * unit the value arrived in, which is what tells the registers apart.
 */
public enum ReadingUnit {
    CUBIC_METRE("m3", ReadingChannel.BFM, BigDecimal.ONE, "m³"),
    KILOLITRE("kL", ReadingChannel.BFM, BigDecimal.ONE),
    LITRE("L", ReadingChannel.BFM, new BigDecimal("0.001"), "litre", "liter"),
    KILOWATT_HOUR("kW.h", ReadingChannel.ELM, BigDecimal.ONE, "kWh"),
    KILOVOLT_AMPERE_HOUR("kV.A.h", ReadingChannel.ELM, MeterRegister.APPARENT_ENERGY, "kVAh"),
    MINUTE("min", ReadingChannel.PDU, BigDecimal.ONE),
    HOUR("h", ReadingChannel.PDU, new BigDecimal("60"), "hr");

    private final String code;
    private final ReadingChannel channel;
    private final MeterRegister register;
    private final BigDecimal factorToStoredUnit;
    private final List<String> otherSpellings;

    /** A unit of the channel's standard register, stored converted to the channel's standard unit. */
    ReadingUnit(String code, ReadingChannel channel, BigDecimal factorToStandardUnit, String... otherSpellings) {
        this(code, channel, MeterRegister.STANDARD, factorToStandardUnit, otherSpellings);
    }

    /** The one unit of another register, stored as it is. */
    ReadingUnit(String code, ReadingChannel channel, MeterRegister register, String... otherSpellings) {
        this(code, channel, register, BigDecimal.ONE, otherSpellings);
    }

    ReadingUnit(String code, ReadingChannel channel, MeterRegister register, BigDecimal factorToStoredUnit,
                String... otherSpellings) {
        this.code = code;
        this.channel = channel;
        this.register = register;
        this.factorToStoredUnit = factorToStoredUnit;
        this.otherSpellings = List.of(otherSpellings);
    }

    /** The UCUM spelling, which is what gets stored. */
    public String code() {
        return code;
    }

    /** The channel whose readings this unit measures. */
    public ReadingChannel channel() {
        return channel;
    }

    /** The register a reading in this unit is taken from. */
    public MeterRegister register() {
        return register;
    }

    /**
     * The unit a value given in this unit is stored in: the channel's standard unit, or this unit itself
     * when it is the only unit of another register.
     */
    public ReadingUnit storedUnit() {
        return register == MeterRegister.STANDARD ? channel.standardUnit().orElseThrow() : this;
    }

    /**
     * {@code value}, given in this unit, expressed in {@link #storedUnit()}. Exact: every factor is a
     * terminating decimal, so the product needs no rounding.
     */
    public BigDecimal toStoredUnit(BigDecimal value) {
        return value.multiply(factorToStoredUnit);
    }

    /**
     * Whether a caller actually declared a unit. Null and blank both read as "not declared", which
     * means the value is in the channel's standard unit.
     */
    public static boolean isDeclared(String code) {
        return code != null && !code.isBlank();
    }

    /**
     * The unit {@code code} names, when it is one of {@code channel}'s units. Matching is trimmed and
     * case-insensitive, and also accepts a unit's other spellings; {@link #code()} is still what gets
     * stored. Empty for null, blank, an unknown code, or a unit of another channel.
     */
    public static Optional<ReadingUnit> parseFor(ReadingChannel channel, String code) {
        if (!isDeclared(code)) {
            return Optional.empty();
        }
        String normalized = code.trim();
        return Arrays.stream(values())
                .filter(unit -> unit.channel == channel && unit.isSpelled(normalized))
                .findFirst();
    }

    private boolean isSpelled(String spelling) {
        return code.equalsIgnoreCase(spelling) || otherSpellings.stream().anyMatch(spelling::equalsIgnoreCase);
    }

    /**
     * The message returned with {@code READING_UNIT_NOT_SUPPORTED}. Free of the submitted value, for
     * the same reason as {@link ReadingChannel#unsupportedDeclarationMessage()}.
     */
    public static String unsupportedMessage(ReadingChannel channel) {
        String allowed = Arrays.stream(values())
                .filter(unit -> unit.channel == channel)
                .map(ReadingUnit::code)
                .collect(Collectors.joining(", "));
        if (allowed.isEmpty()) {
            return "Channel " + channel.name() + " does not accept a reading_unit.";
        }
        return "Unsupported reading_unit for channel " + channel.name() + ". Allowed values are: " + allowed;
    }
}
