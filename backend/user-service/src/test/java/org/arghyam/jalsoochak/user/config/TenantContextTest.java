package org.arghyam.jalsoochak.user.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TenantContext")
class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("getSchema returns null before any value is set")
    void getSchemaReturnsNullInitially() {
        TenantContext.clear();
        assertThat(TenantContext.getSchema()).isNull();
    }

    @Test
    @DisplayName("setSchema stores and getSchema retrieves the value")
    void setAndGetSchema() {
        TenantContext.setSchema("tenant_mp");
        assertThat(TenantContext.getSchema()).isEqualTo("tenant_mp");
    }

    @Test
    @DisplayName("clear removes the stored schema")
    void clearRemovesSchema() {
        TenantContext.setSchema("tenant_tr");
        TenantContext.clear();
        assertThat(TenantContext.getSchema()).isNull();
    }

    @Test
    @DisplayName("overwriting schema replaces the previous value")
    void overwriteSchema() {
        TenantContext.setSchema("tenant_old");
        TenantContext.setSchema("tenant_new");
        assertThat(TenantContext.getSchema()).isEqualTo("tenant_new");
    }

    @Test
    @DisplayName("schema is isolated per thread")
    void threadIsolation() throws InterruptedException {
        TenantContext.setSchema("tenant_main");

        String[] threadValue = new String[1];
        Thread t = new Thread(() -> {
            threadValue[0] = TenantContext.getSchema();
            TenantContext.setSchema("tenant_thread");
        });
        t.start();
        t.join();

        // Thread should see null (no value set in that thread)
        assertThat(threadValue[0]).isNull();
        // Main thread value is unaffected
        assertThat(TenantContext.getSchema()).isEqualTo("tenant_main");
    }
}
