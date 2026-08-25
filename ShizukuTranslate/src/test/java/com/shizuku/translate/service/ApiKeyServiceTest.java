package com.shizuku.translate.service;

import com.shizuku.translate.entity.ApiKey;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.repository.ApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private UserService userService;

    @InjectMocks
    private ApiKeyService service;

    @Test
    void newKeyStoresOnlyPrefixAndHashAndReturnsPlaintextOnce() {
        User user = new User();
        user.setId(1L);
        when(userService.findByUsername("alice")).thenReturn(user);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(invocation -> {
            ApiKey key = invocation.getArgument(0);
            key.setId(7L);
            return key;
        });

        ApiKeyService.CreatedApiKey created = service.createApiKey("alice", "test");

        assertTrue(created.rawKey().startsWith("sk-st-"));
        assertNotNull(created.entity().getKeyHash());
        assertEquals(ApiKeyService.maskPrefix(created.rawKey()), created.entity().getKeyPrefix());
        assertNull(created.entity().getLegacyKeyValue());
        assertFalse(created.entity().getKeyHash().equals(created.rawKey()));
        verify(apiKeyRepository).save(created.entity());
    }

    @Test
    void authenticatesNewKeyByHash() {
        String raw = "sk-st-existing";
        ApiKey key = ApiKey.builder().keyHash(ApiKeyService.hashApiKey(raw)).active(true).build();
        when(apiKeyRepository.findByKeyHashAndActiveTrue(ApiKeyService.hashApiKey(raw)))
                .thenReturn(Optional.of(key));

        assertSame(key, service.authenticate(raw).orElseThrow());
        verify(apiKeyRepository, never()).findByLegacyKeyValueAndActiveTrue(any());
    }

    @Test
    void migratesLegacyPlaintextKeyOnFirstAuthentication() {
        String raw = "legacy-secret";
        ApiKey key = ApiKey.builder().legacyKeyValue(raw).active(true).build();
        when(apiKeyRepository.findByKeyHashAndActiveTrue(ApiKeyService.hashApiKey(raw)))
                .thenReturn(Optional.empty());
        when(apiKeyRepository.findByLegacyKeyValueAndActiveTrue(raw)).thenReturn(Optional.of(key));
        when(apiKeyRepository.save(key)).thenReturn(key);

        assertSame(key, service.authenticate(raw).orElseThrow());
        assertEquals(ApiKeyService.hashApiKey(raw), key.getKeyHash());
        assertEquals(ApiKeyService.maskPrefix(raw), key.getKeyPrefix());
        assertNull(key.getLegacyKeyValue());
        verify(apiKeyRepository).save(key);
    }
}
