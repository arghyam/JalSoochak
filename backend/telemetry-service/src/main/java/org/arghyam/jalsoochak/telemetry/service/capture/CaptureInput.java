package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.service.OcrRetryMode;

import java.math.BigDecimal;

/**
 * What a submission brings to the {@link ReadingCapture} step. The channel is resolved before
 * capture, because it decides which units are accepted and, for a photo, how it is read.
 *
 * @param schemaName         tenant schema the submission belongs to
 * @param tenantId           tenant whose OCR settings apply
 * @param operatorId         operator the reading is credited to
 * @param schemeId           scheme the reading is for
 * @param channel            the reading channel, declared or taken from the operator's preference
 * @param readingUrl         the meter photo; null when none was sent
 * @param submittedValue     the value typed in or asserted; null when only a photo was sent
 * @param readingUnit        the unit the submitter declared, unchecked; null or blank means the
 *                           channel's standard unit
 * @param externallyAsserted whether {@code submittedValue} came from an integrating system rather
 *                           than being typed in
 * @param ocrRetryMode       whether OCR goes through the retry and circuit-breaker layer
 */
public record CaptureInput(
        String schemaName,
        Integer tenantId,
        Long operatorId,
        Long schemeId,
        ReadingChannel channel,
        String readingUrl,
        BigDecimal submittedValue,
        String readingUnit,
        boolean externallyAsserted,
        OcrRetryMode ocrRetryMode) {
}
