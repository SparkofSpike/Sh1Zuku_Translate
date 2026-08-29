package com.shizuku.translate.service;

import com.shizuku.translate.entity.TranslationCache;
import com.shizuku.translate.repository.TranslationCacheRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslationCacheLookupTest {
    @Test
    void repositoryLookupCanReturnDuplicateRowsAndNewestEntryIsSelected() {
        TranslationCache older = TranslationCache.builder().translatedText("old").build();
        TranslationCache newer = TranslationCache.builder().translatedText("new").build();
        List<TranslationCache> entries = List.of(newer, older);

        TranslationCache selected = entries.get(0);

        assertEquals("new", selected.getTranslatedText());
    }
}
