package org.arghyam.jalsoochak.scheme.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.IntStream;

import org.arghyam.jalsoochak.scheme.config.properties.AppProperties;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;

/**
 * Unit tests for the Single Tenant Mode startup invariant in scheme-service, which performs the
 * same SUPER_STATE_ADMIN role expansion as user-service: it must not boot when SINGLE_TENANT_MODE
 * is on for a database that already holds more than one ACTIVE tenant.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Single Tenant Mode Startup Validator - scheme-service")
class SingleTenantModeStartupValidatorTest {

    @Mock
    private AppProperties appProperties;

    @Mock
    private SchemeDbRepository schemeDbRepository;

    @InjectMocks
    private SingleTenantModeStartupValidator validator;

    @Nested
    @DisplayName("Single Tenant Mode on")
    class SingleTenantModeOn {

        @Test
        @DisplayName("fails startup when more than one tenant is ACTIVE")
        void validateSingleTenantMode_failsWhenMoreThanOneTenantIsActive() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(schemeDbRepository.findActiveTenantStateCodes()).thenReturn(List.of("MP", "RJ", "UP"));

            assertThatThrownBy(() -> validator.validateSingleTenantMode())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SINGLE_TENANT_MODE=true")
                    .hasMessageContaining("app.single-tenant-mode")
                    .hasMessageContaining("3 tenants are ACTIVE")
                    .hasMessageContaining("MP, RJ, UP");
        }

        @Test
        @DisplayName("does not bother reading DEGRADED tenants once startup is already doomed")
        void validateSingleTenantMode_skipsTheDegradedQueryWhenAlreadyFailing() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(schemeDbRepository.findActiveTenantStateCodes()).thenReturn(List.of("MP", "UP"));

            assertThatThrownBy(() -> validator.validateSingleTenantMode())
                    .isInstanceOf(IllegalStateException.class);

            verify(schemeDbRepository, never()).findDegradedTenantStateCodes();
        }

        @Test
        @DisplayName("truncates the state-code list in the failure message")
        void validateSingleTenantMode_truncatesLongStateCodeLists() {
            List<String> twelve = IntStream.range(0, 12).mapToObj(i -> "T" + i).toList();
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(schemeDbRepository.findActiveTenantStateCodes()).thenReturn(twelve);

            assertThatThrownBy(() -> validator.validateSingleTenantMode())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("12 tenants are ACTIVE")
                    .hasMessageContaining("and 2 more")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("T11"));
        }

        @Test
        @DisplayName("starts when exactly one tenant is ACTIVE")
        void validateSingleTenantMode_passesWhenExactlyOneTenantIsActive() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(schemeDbRepository.findActiveTenantStateCodes()).thenReturn(List.of("MP"));
            when(schemeDbRepository.findDegradedTenantStateCodes()).thenReturn(List.of());

            assertThatCode(() -> validator.validateSingleTenantMode()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("starts when no tenant is ACTIVE yet")
        void validateSingleTenantMode_passesWhenNoTenantIsActive() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(schemeDbRepository.findActiveTenantStateCodes()).thenReturn(List.of());
            when(schemeDbRepository.findDegradedTenantStateCodes()).thenReturn(List.of());

            assertThatCode(() -> validator.validateSingleTenantMode()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("starts, warning only, when DEGRADED tenants push the loginable count above one")
        void validateSingleTenantMode_warnsButStartsWhenDegradedTenantsAreAlsoLoginable() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(schemeDbRepository.findActiveTenantStateCodes()).thenReturn(List.of("MP"));
            when(schemeDbRepository.findDegradedTenantStateCodes()).thenReturn(List.of("RJ", "UP"));

            assertThatCode(() -> validator.validateSingleTenantMode()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Multi Tenant Mode")
    class MultiTenantMode {

        @Test
        @DisplayName("skips the check entirely and never queries the tenant table")
        void validateSingleTenantMode_skipsTheCheckInMultiTenantMode() {
            when(appProperties.isSingleTenantMode()).thenReturn(false);

            assertThatCode(() -> validator.validateSingleTenantMode()).doesNotThrowAnyException();

            verifyNoInteractions(schemeDbRepository);
        }
    }

    @Nested
    @DisplayName("Tenant table unreadable")
    class TenantTableUnreadable {

        @Test
        @DisplayName("starts with a warning when common_schema has not been migrated yet")
        void validateSingleTenantMode_toleratesAMissingTenantTable() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(schemeDbRepository.findActiveTenantStateCodes())
                    .thenThrow(new BadSqlGrammarException("query", "SELECT state_code", new SQLException("42P01")));

            assertThatCode(() -> validator.validateSingleTenantMode()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("fails startup when the tenant table cannot be read at all")
        void validateSingleTenantMode_failsWhenTheTenantTableCannotBeRead() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(schemeDbRepository.findActiveTenantStateCodes())
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));

            assertThatThrownBy(() -> validator.validateSingleTenantMode())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be read")
                    .hasMessageContaining("SINGLE_TENANT_MODE=false")
                    .hasCauseInstanceOf(DataAccessResourceFailureException.class);
        }
    }
}
