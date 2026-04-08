package org.arghyam.jalsoochak.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InviteRequestDTO {
    @NotBlank(message = "Email is required")
    @Email(message = "Please provide a valid email address")
    private String email;

    @NotBlank(message = "Role is required")
    @Pattern(regexp = "SUPER_USER|STATE_ADMIN|SUPER_STATE_ADMIN", message = "Role must be one of: SUPER_USER, STATE_ADMIN, SUPER_STATE_ADMIN")
    private String role;

    @Pattern(regexp = "^[A-Z]{2,4}$", message = "Tenant code must be 2-4 uppercase letters")
    private String tenantCode;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\d{10}$", message = "Phone number must be 10 digits")
    private String phoneNumber;
}
