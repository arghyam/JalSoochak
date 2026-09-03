package org.arghyam.jalsoochak.tenant.config;

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

import org.arghyam.jalsoochak.tenant.config.properties.AppProperties;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Unit tests for the Single Tenant Mode startup invariant: the service must not boot when
 * SINGLE_TENANT_MODE is on for a database that already holds more than one ACTIVE tenant.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Single Tenant Mode Startup Validator - tenant-service")
class SingleTenantModeStartupValidatorTest {

    private static final String SELECT_ACTIVE_TENANTS =
            "SELECT state_code FROM common_schema.tenant_master_table WHERE status = 3";

    /** PostgreSQL undefined_table: common_schema.tenant_master_table has not been created yet. */
    private static final String UNDEFINED_TABLE = "42P01";

    /** PostgreSQL invalid_schema_name: common_schema itself is absent. */
    private static final String INVALID_SCHEMA_NAME = "3F000";

    /** PostgreSQL undefined_column: SQLState class 42 too, but the table may hold ACTIVE tenants. */
    private static final String UNDEFINED_COLUMN = "42703";

    /** PostgreSQL insufficient_privilege: the role cannot SELECT the tenant table. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /**
     * Builds the exception Spring raises for a SQLState class 42 failure. The SQLState must go in
     * {@link SQLException}'s <em>second</em> argument — the single-argument constructor sets the
     * reason and leaves the SQLState null.
     */
    private static BadSqlGrammarException badSqlGrammar(String reason, String sqlState) {
        return new BadSqlGrammarException("query", SELECT_ACTIVE_TENANTS,
                new SQLException(reason, sqlState));
    }

    @Mock
    private AppProperties appProperties;

    @Mock
    private TenantCommonRepository tenantCommonRepository;

    @InjectMocks
    private SingleTenantModeStartupValidator validator;

    @Nested
    @DisplayName("Single Tenant Mode on")
    class SingleTenantModeOn {

        @Test
        @DisplayName("fails startup when more than one tenant is ACTIVE")
        void validateSingleTenantMode_failsWhenMoreThanOneTenantIsActive() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.findActiveTenantStateCodes()).thenReturn(List.of("MP", "RJ", "UP"));

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
            when(tenantCommonRepository.findActiveTenantStateCodes()).thenReturn(List.of("MP", "UP"));

            assertThatThrownBy(() -> validator.validateSingleTenantMode())
                    .isInstanceOf(IllegalStateException.class);

            verify(tenantCommonRepository, never()).findDegradedTenantStateCodes();
        }

        @Test
        @DisplayName("truncates the state-code list in the failure message")
        void validateSingleTenantMode_truncatesLongStateCodeLists() {
            List<String> twelve = IntStream.range(0, 12).mapToObj(i -> "T" + i).toList();
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.findActiveTenantStateCodes()).thenReturn(twelve);

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
            when(tenantCommonRepository.findActiveTenantStateCodes()).thenReturn(List.of("MP"));
            when(tenantCommonRepository.findDegradedTenantStateCodes()).thenReturn(List.of());

            assertThatCode(() -> validator.validateSingleTenantMode()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("starts when no tenant is ACTIVE yet")
        void validateSingleTenantMode_passesWhenNoTenantIsActive() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.findActiveTenantStateCodes()).thenReturn(List.of());
            when(tenantCommonRepository.findDegradedTenantStateCodes()).thenReturn(List.of());

            assertThatCode(() -> validator.validateSingleTenantMode()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("starts, warning only, when DEGRADED tenants push the loginable count above one")
        void validateSingleTenantMode_warnsButStartsWhenDegradedTenantsAreAlsoLoginable() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.findActiveTenantStateCodes()).thenReturn(List.of("MP"));
            when(tenantCommonRepository.findDegradedTenantStateCodes()).thenReturn(List.of("RJ", "UP"));

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

            verifyNoInteractions(tenantCommonRepository);
        }
    }

    @Nested
    @DisplayName("Tenant table unreadable")
    class TenantTableUnreadable {

        @Test
        @DisplayName("starts with a warning when common_schema has not been migrated yet")
        void validateSingleTenantMode_toleratesAMissingTenantTable() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.findActiveTenantStateCodes()).thenThrow(
                    badSqlGrammar("relation \"tenant_master_table\" does not exist", UNDEFINED_TABLE));

            assertThatCode(() -> validator.validateSingleTenantMode()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("starts with a warning when common_schema itself does not exist")
        void validateSingleTenantMode_toleratesAMissingCommonSchema() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.findActiveTenantStateCodes())
                    .thenThrow(new UncategorizedSQLException("query", SELECT_ACTIVE_TENANTS,
                            new SQLException("schema \"common_schema\" does not exist",
                                    INVALID_SCHEMA_NAME)));

            assertThatCode(() -> validator.validateSingleTenantMode()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("fails startup on an undefined column: the table may still hold ACTIVE tenants")
        void validateSingleTenantMode_failsOnAnUndefinedColumn() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.findActiveTenantStateCodes())
                    .thenThrow(badSqlGrammar("column \"status\" does not exist", UNDEFINED_COLUMN));

            assertThatThrownBy(() -> validator.validateSingleTenantMode())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be read")
                    .hasMessageContaining("SINGLE_TENANT_MODE=false")
                    .hasCauseInstanceOf(BadSqlGrammarException.class);
        }

        @Test
        @DisplayName("fails startup when the role lacks SELECT on the tenant table")
        void validateSingleTenantMode_failsOnInsufficientPrivilege() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.findActiveTenantStateCodes()).thenThrow(badSqlGrammar(
                    "permission denied for table tenant_master_table", INSUFFICIENT_PRIVILEGE));

            assertThatThrownBy(() -> validator.validateSingleTenantMode())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be read")
                    .hasCauseInstanceOf(BadSqlGrammarException.class);
        }

        @Test
        @DisplayName("fails startup when the failure carries no SQLState at all")
        void validateSingleTenantMode_failsWhenTheSqlStateIsUnknown() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.findActiveTenantStateCodes())
                    .thenThrow(badSqlGrammar("something went wrong", null));

            assertThatThrownBy(() -> validator.validateSingleTenantMode())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be read");
        }

        @Test
        @DisplayName("fails startup when the tenant table cannot be read at all")
        void validateSingleTenantMode_failsWhenTheTenantTableCannotBeRead() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.findActiveTenantStateCodes())
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));

            assertThatThrownBy(() -> validator.validateSingleTenantMode())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be read")
                    .hasMessageContaining("SINGLE_TENANT_MODE=false")
                    .hasCauseInstanceOf(DataAccessResourceFailureException.class);
        }
    }
}
