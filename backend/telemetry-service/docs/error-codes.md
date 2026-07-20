# Telemetry error codes

Telemetry reading responses expose machine-readable failures in `data.error_code`.
The field is omitted on successful responses.

The closed set of supported values is defined by `TelemetryErrorCode`:

| Code | Meaning |
| --- | --- |
| `FLOW_VISION_FAILED` | FlowVision OCR processing failed before a result could be returned. |
| `FLOW_VISION_REJECTED` | FlowVision rejected the image. |
| `UNREADABLE_IMAGE` | OCR completed, but no valid meter reading could be extracted. |
| `DUPLICATE_IMAGE` | The submitted image was already processed. |
| `VALIDATION_FAILED` | Request JSON was parsed, but bean validation rejected the payload. |
| `MALFORMED_REQUEST` | Request JSON could not be parsed. |
| `INVALID_API_KEY` | The supplied telemetry API key was missing or invalid. |
| `PROCESSING_FAILED` | The service failed while processing the reading and no more specific code applies. |
| `OPERATOR_NOT_MAPPED_TO_SCHEME` | The operator could not be mapped to the submitted scheme. |
| `SCHEME_NOT_FOUND` | The submitted scheme could not be found. |
| `OPERATOR_NOT_FOUND` | The submitting operator could not be found. |
| `SERVER_ERROR` | An internal server error occurred. |
| `BAD_REQUEST` | The request was rejected and no more specific bad-request code applies. |
| `REQUEST_FAILED` | The request failed and no more specific code applies. |
