package com.shizuku.translate.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    @Test
    void rejectsBlankSecret() {
        assertThrows(IllegalStateException.class,
                () -> new JwtTokenProvider("", "shizuku-translate", 86400000));
    }

    @Test
    void rejectsKnownDefaultSecret() {
        assertThrows(IllegalStateException.class,
                () -> new JwtTokenProvider("this-is-a-default-secret-key", "shizuku-translate", 86400000));
    }

    @Test
    void validatesIssuerAndSubject() {
        JwtTokenProvider provider = new JwtTokenProvider(
                "0123456789abcdef0123456789abcdef", "shizuku-translate", 86400000);
        String token = provider.generateToken("alice");

        assertTrue(provider.validateToken(token));
        assertEquals("alice", provider.getUsernameFromToken(token));

        JwtTokenProvider otherIssuer = new JwtTokenProvider(
                "0123456789abcdef0123456789abcdef", "other-issuer", 86400000);
        assertFalse(otherIssuer.validateToken(token));
    }
}
