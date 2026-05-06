package org.arghyam.jalsoochak.telemetry.service;

public final class AnomalyConstants {

    private AnomalyConstants() {
    }

    public static final int TYPE_UNREADABLE_IMAGE = 1;
    public static final int TYPE_MANUAL_OVERRIDE = 2;
    public static final int TYPE_CONSECUTIVE_OVERRIDE_5_DAYS = 3;
    public static final int TYPE_DUPLICATE_IMAGE_SUBMISSION = 4;
    public static final int TYPE_READING_LESS_THAN_PREVIOUS = 5;
    // Operator-reported "No Water Supply" (e.g. from issue report menu).
    public static final int TYPE_NO_WATER_SUPPLY = 6;
    // Daily supply anomalies derived from water quantity vs water norm thresholds.
    public static final int TYPE_LOW_WATER_SUPPLY = 7;
    public static final int TYPE_OVER_WATER_SUPPLY = 8;
    // No meter reading submission due to operational issues (e.g. meter not working/damaged/others).
    public static final int TYPE_NO_SUBMISSION = 9;
    // FlowVision integration error (API/client/schema errors).
    public static final int TYPE_FLOWVISION_INTEGRATION_ERROR = 10;

    public static final int STATUS_OPEN = 1;
}
