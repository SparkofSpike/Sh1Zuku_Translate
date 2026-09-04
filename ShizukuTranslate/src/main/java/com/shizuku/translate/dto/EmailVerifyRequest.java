package com.shizuku.translate.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Confirm a verification code for the logged-in user's account email.
 * The address may equal the current one (verify an existing account)
 * or be a new address (change email); either way verification succeeds
 * only with a code just sent to that address.
 */
public class EmailVerifyRequest {
    @NotBlank @Email @Size(max = 100)
    private String email;
    @NotBlank @Size(min = 4, max = 10)
    private String code;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}
