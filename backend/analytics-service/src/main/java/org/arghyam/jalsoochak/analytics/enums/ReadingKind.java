package org.arghyam.jalsoochak.analytics.enums;

/**
 * What one reading on a channel measures, which decides how much of the channel's quantity a day
 * has. The channel's calculator then only turns that amount into litres.
 */
public enum ReadingKind {

    /**
     * A running total that only goes up (a flow meter's m&sup3;, an electricity meter's kWh). The
     * day's amount is its latest reading minus the latest reading before the day.
     */
    METER_INDEX,

    /**
     * An amount for one period on its own (minutes one pump run lasted). The day's amount is the
     * sum of its submissions.
     */
    PERIOD_AMOUNT
}
