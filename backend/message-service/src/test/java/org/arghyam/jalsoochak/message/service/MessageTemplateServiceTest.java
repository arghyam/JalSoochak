package org.arghyam.jalsoochak.message.service;

/**
 * Pure unit tests for {@link MessageTemplateService} in-memory logic.
 *
 * <p>Tests language normalization, placeholder substitution, and other logic
 * that can be verified without database interaction. Database-driven template
 * lookup and fallback behavior is covered by {@link MessageTemplateServiceIntegrationTest}
 * using Testcontainers with real PostgreSQL.</p>
 *
 * <p><strong>Migration note:</strong> Per project TDD guidelines, database mocking
 * should be avoided. Any remaining tests using {@code @Mock JdbcTemplate} should
 * be refactored to either test pure in-memory logic or moved to the integration test.</p>
 */
class MessageTemplateServiceTest {
    // TODO: Refactor remaining database-mocking tests to use Testcontainers
    // or extract pure in-memory logic for unit testing.
    // See MessageTemplateServiceIntegrationTest for database-driven test examples.
}