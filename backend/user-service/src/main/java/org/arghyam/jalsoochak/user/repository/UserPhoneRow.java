package org.arghyam.jalsoochak.user.repository;

/**
 * A tenant user's id, decrypted phone number and user type ({@code user_type_master_table.c_name}).
 */
public record UserPhoneRow(
        long id,
        String phoneNumber,
        String userType
) {
}
