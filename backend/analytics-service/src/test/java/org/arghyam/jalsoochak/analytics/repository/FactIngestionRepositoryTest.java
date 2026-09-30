package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The order {@link FactIngestionRepository#lockSchemes} takes its locks in, which a database cannot
 * show once they are held. {@code FactIngestionRepositoryIntegrationTest} covers the locks themselves.
 */
@ExtendWith(MockitoExtension.class)
class FactIngestionRepositoryTest {

    private static final int TENANT = 1;

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PreparedStatement statement;

    @Test
    @SuppressWarnings("unchecked")
    void lockSchemes_takesEachSchemesLockOnceInAscendingKeyOrder() throws SQLException {
        // Two transactions that lock the same schemes then queue on the same first lock, instead of
        // each holding the lock the other waits for.
        new FactIngestionRepository(jdbcTemplate).lockSchemes(TENANT, List.of(12, 3, 12, 7));

        ArgumentCaptor<PreparedStatementSetter> setters = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbcTemplate, times(3))
                .query(eq("SELECT pg_advisory_xact_lock(?, ?)"), setters.capture(), any(ResultSetExtractor.class));
        for (PreparedStatementSetter setter : setters.getAllValues()) {
            setter.setValues(statement);
        }
        InOrder order = inOrder(statement);
        order.verify(statement).setInt(2, Objects.hash(TENANT, 3));
        order.verify(statement).setInt(2, Objects.hash(TENANT, 7));
        order.verify(statement).setInt(2, Objects.hash(TENANT, 12));
    }
}
