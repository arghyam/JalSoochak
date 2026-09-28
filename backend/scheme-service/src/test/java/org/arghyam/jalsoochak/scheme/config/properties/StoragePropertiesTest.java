package org.arghyam.jalsoochak.scheme.config.properties;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class StoragePropertiesTest {

    private static StorageProperties populated() {
        StorageProperties p = new StorageProperties();
        p.setEnabled(true);
        p.setEndpoint("http://object-store:9000");
        p.setRegion("ap-south-1");
        p.setAccessKey("access");
        p.setSecretKey("secret");
        p.setReportsBucket("bucket");
        p.setPresignedBaseUrl("https://files.example.org");
        p.setPresignedTtlSeconds(600L);
        return p;
    }

    @Test
    void defaults() {
        StorageProperties p = new StorageProperties();
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getEndpoint()).isNull();
        assertThat(p.getRegion()).isEqualTo("us-east-1");
        assertThat(p.getReportsBucket()).isEqualTo("jalsoochak-reports");
        assertThat(p.getPresignedBaseUrl()).isNull();
        assertThat(p.getPresignedTtlSeconds()).isEqualTo(3600L);
    }

    @Test
    void equalsAndHashCode_matchForSameValues() {
        StorageProperties a = populated();
        StorageProperties b = populated();
        assertThat(a).isEqualTo(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(null).isNotEqualTo("other");
        assertThat(new StorageProperties()).isEqualTo(new StorageProperties());
        assertThat(new StorageProperties().hashCode()).isEqualTo(new StorageProperties().hashCode());
        assertThat(a.toString()).contains("object-store", "bucket");
    }

    @Test
    void equals_detectsEachFieldDifference() {
        List<Consumer<StorageProperties>> mutations = List.of(
                p -> p.setEnabled(false),
                p -> p.setEndpoint("http://other"),
                p -> p.setEndpoint(null),
                p -> p.setRegion("us-west-2"),
                p -> p.setRegion(null),
                p -> p.setAccessKey("other"),
                p -> p.setAccessKey(null),
                p -> p.setSecretKey("other"),
                p -> p.setSecretKey(null),
                p -> p.setReportsBucket("other"),
                p -> p.setReportsBucket(null),
                p -> p.setPresignedBaseUrl("https://other"),
                p -> p.setPresignedBaseUrl(null),
                p -> p.setPresignedTtlSeconds(1L)
        );
        for (Consumer<StorageProperties> mutation : mutations) {
            StorageProperties changed = populated();
            mutation.accept(changed);
            assertThat(changed).isNotEqualTo(populated());
            assertThat(populated()).isNotEqualTo(changed);
        }
    }

    @Test
    void equals_respectsSubclassCanEqual() {
        StorageProperties sub = new StorageProperties() {
            @Override
            protected boolean canEqual(Object other) {
                return false;
            }
        };
        assertThat(new StorageProperties()).isNotEqualTo(sub);
    }
}
