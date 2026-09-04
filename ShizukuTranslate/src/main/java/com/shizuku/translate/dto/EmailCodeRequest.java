package com.shizuku.translate.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request a verification code for an email address.
 * Used both by the registration form (anonymous) and by a logged-in
 * user who wants to verify / change the address on their account.
 */
public class EmailCodeRequest {
    @NotBlank @Email @Size(max = 100)
    private String email;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
