# Telemetry error codes

Telemetry reading responses expose machine-readable failures in `data.error_code`.
The field is omitted on successful responses.

The closed set of supported values is defined by `TelemetryErrorCode`:

| Code | Meaning |
| --- | --- |
| `FLOW_VISION_FAILED` | OCR processing failed before a result could be returned. |
| `FLOW_VISION_REJECTED` | Reserved for an explicit OCR provider rejection; not currently emitted. |
| `UNREADABLE_IMAGE` | OCR completed, but no valid meter reading could be extracted. |
| `NO_METER_DETECTED` | The OCR provider found no meter in the photo (rotated, unrelated or random image). Ask for a photo of the meter, not a clearer one. |
| `DUPLICATE_IMAGE` | The submitted image was already processed. |
| `VALIDATION_FAILED` | Request JSON was parsed, but bean validation rejected the payload. |
| `MALFORMED_REQUEST` | Request JSON could not be parsed. |
| `INVALID_API_KEY` | The supplied telemetry API key was missing or invalid. |
| `CHANNEL_NOT_SUPPORTED` | The submitted `channel` is not one of BFM, ELM, PDU, IOT, MAN. |
| `READING_UNIT_NOT_SUPPORTED` | The `reading_unit` is not one the channel accepts, or a photo-only submission names a unit other than the one OCR reads (m3 for BFM; kWh or kVAh for ELM), or a correction would move a photo-read reading to the other meter register. |
| `IMAGE_NOT_SUPPORTED_FOR_CHANNEL` | Only a photo was sent, and the channel cannot read one: PDU always, and ELM when the tenant has no ELM OCR provider. A photo sent with a `confirmed_reading` is accepted and not read. |
| `PROCESSING_FAILED` | The service failed while processing the reading and no more specific code applies. |
| `OPERATOR_NOT_MAPPED_TO_SCHEME` | The operator could not be mapped to the submitted scheme. |
| `SCHEME_NOT_FOUND` | The submitted scheme could not be found. |
| `TENANT_NOT_FOUND` | The tenant named by `X-Tenant-Code` could not be found. |
| `OPERATOR_NOT_FOUND` | The submitting operator could not be found. |
| `SERVER_ERROR` | An internal server error occurred. |
| `BAD_REQUEST` | The request was rejected and no more specific bad-request code applies. |
| `REQUEST_FAILED` | The request failed and no more specific code applies. |
| `ABNORMAL_READING` | The reading is not usable: its implied daily volume is implausible for the scheme, or a PDU run (or a scheme's PDU runs on one day) exceeds 1,440 minutes. Deliberately vague; the message never echoes the limit. |
