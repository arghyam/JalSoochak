package org.arghyam.jalsoochak.telemetry.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LanguageCatalogRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void loadFailureIsNotCachedAndIsRetriedOnNextLookup() {
        LanguageCatalogRepository repository = new LanguageCatalogRepository(jdbcTemplate);
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));

        // Failure -> empty fallback result, but nothing is cached...
        assertTrue(repository.findLanguageIdByAlias("hindi").isEmpty());
        // ...so a second lookup re-queries rather than serving a pinned empty catalog.
        assertTrue(repository.findLocaleCodeByAlias("hindi").isEmpty());

        verify(jdbcTemplate, times(2)).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    void successfulEmptyCatalogIsCached() {
        LanguageCatalogRepository repository = new LanguageCatalogRepository(jdbcTemplate);
        // A successful query that yields no rows is a valid (empty) catalog and should be cached.
        doNothing().when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));

        assertTrue(repository.findLanguageIdByAlias("hindi").isEmpty());
        assertTrue(repository.findLocaleCodeByAlias("english").isEmpty());

        verify(jdbcTemplate, times(1)).query(anyString(), any(RowCallbackHandler.class));
    }
}
