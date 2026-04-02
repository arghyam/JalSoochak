package org.arghyam.jalsoochak.user.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Standard API success response wrapper")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponseDTO<T> {

    @Schema(description = "HTTP status code", example = "200")
    private int status;

    @Schema(description = "Human-readable result message", example = "Operation successful")
    private String message;

    @Schema(description = "Response payload (absent for void operations)", accessMode = Schema.AccessMode.READ_ONLY)
    private T data;

    private ApiResponseDTO(int status, String message, T data) {
        this.status = status;
        this.message = message;
        this.data = data;
    }

    /** Use for responses that carry a payload. */
    public static <T> ApiResponseDTO<T> of(int status, String message, T data) {
        return new ApiResponseDTO<>(status, message, data);
    }

    /** Use for void responses (e.g. logout, deactivate). */
    public static ApiResponseDTO<Void> of(int status, String message) {
        return new ApiResponseDTO<>(status, message, null);
    }

    public int getStatus() { return status; }
    public String getMessage() { return message; }
    public T getData() { return data; }
}
