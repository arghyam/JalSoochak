package org.arghyam.jalsoochak.user.clients;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Google reCAPTCHA {@code siteverify} response.
 *
 * <p>Only the v2 pass/fail fields are mapped. A future move to reCAPTCHA v3 would additively add
 * {@code score} and {@code action} here without breaking the v2 path.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecaptchaVerifyResponse(
        boolean success,
        @JsonProperty("challenge_ts") String challengeTs,
        String hostname,
        @JsonProperty("error-codes") List<String> errorCodes
) {}
