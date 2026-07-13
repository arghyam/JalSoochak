# Telemetry error codes

Telemetry reading responses expose machine-readable failures in `data.error_code`.
The field is omitted on successful responses.

The closed set of supported values is defined by `TelemetryErrorCode`:

| Code | Meaning |
| --- | --- |
| `flowVisionFailed` | FlowVision OCR processing failed before a result could be returned. |
| `flowVisionRejected` | FlowVision rejected the image. |
| `unreadableImage` | OCR completed, but no valid meter reading could be extracted. |
| `duplicateImage` | The submitted image was already processed. |
| `validationFailed` | Request JSON was parsed, but bean validation rejected the payload. |
| `malformedRequest` | Request JSON could not be parsed. |
| `invalidApiKey` | The supplied telemetry API key was missing or invalid. |
| `processingFailed` | The service failed while processing the reading and no more specific code applies. |
| `operatorNotMappedToScheme` | The operator could not be mapped to the submitted scheme. |
| `schemeNotFound` | The submitted scheme could not be found. |
| `operatorNotFound` | The submitting operator could not be found. |
| `serverError` | An internal server error occurred. |
| `badRequest` | The request was rejected and no more specific bad-request code applies. |
| `requestFailed` | The request failed and no more specific code applies. |
