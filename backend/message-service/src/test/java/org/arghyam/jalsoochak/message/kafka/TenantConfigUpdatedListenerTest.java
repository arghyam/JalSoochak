package org.arghyam.jalsoochak.message.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.arghyam.jalsoochak.message.channel.TenantChannelProviders;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PER-TENANT-PROVIDERS: unit tests for {@link TenantConfigUpdatedListener}.
 *
 * <p>The payloads here are written out in full rather than built from a shared fixture, because
 * they are a contract with another service: tenant-service's {@code TenantEventListener} produces
 * exactly this shape, and a test that constructed the JSON from the same constants the listener
 * reads would pass however the two drifted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantConfigUpdatedListener Tests")
class TenantConfigUpdatedListenerTest {

    @Mock
    private TenantChannelProviders channelProviders;

    private TenantConfigUpdatedListener listener;

    @BeforeEach
    void setUp() {
        listener = new TenantConfigUpdatedListener(channelProviders, new ObjectMapper());
    }

    @Test
    @DisplayName("an email settings key evicts the email channel only")
    void emailSettingsKeyEvictsEmail() {
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":7,"stateCode":"MP",
                 "configKeys":["EMAIL_PROVIDER_SETTINGS"]}
                """);

        verify(channelProviders).evict(7, MessagingChannel.EMAIL);
        verifyNoMoreInteractions(channelProviders);
    }

    @Test
    @DisplayName("an SMS settings key evicts the SMS channel only")
    void smsSettingsKeyEvictsSms() {
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":7,"stateCode":"MP",
                 "configKeys":["SMS_PROVIDER_SETTINGS"]}
                """);

        verify(channelProviders).evict(7, MessagingChannel.SMS);
        verifyNoMoreInteractions(channelProviders);
    }

    @Test
    @DisplayName("an event naming both keys evicts both channels")
    void bothKeysEvictBothChannels() {
        // What a collection PUT carrying both channels produces.
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":7,"stateCode":"MP",
                 "configKeys":["EMAIL_PROVIDER_SETTINGS","SMS_PROVIDER_SETTINGS"]}
                """);

        verify(channelProviders).evict(7, MessagingChannel.EMAIL);
        verify(channelProviders).evict(7, MessagingChannel.SMS);
    }

    @Test
    @DisplayName("a settings key mixed with unrelated keys still evicts its channel")
    void relevantKeyAmongUnrelatedOnesStillEvicts() {
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":7,"stateCode":"MP",
                 "configKeys":["REGULARITY_THRESHOLD_PERCENT","EMAIL_PROVIDER_SETTINGS"]}
                """);

        verify(channelProviders).evict(7, MessagingChannel.EMAIL);
        verifyNoMoreInteractions(channelProviders);
    }

    @Test
    @DisplayName("an event naming only unrelated keys evicts nothing")
    void unrelatedKeysEvictNothing() {
        // tenant-service publishes this event for every config write in the platform. Clearing the
        // provider cache on all of them would make the TTL the real mechanism and the event noise.
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":7,"stateCode":"MP",
                 "configKeys":["REGULARITY_THRESHOLD_PERCENT","DAILY_SITUATION_REPORT_TIME"]}
                """);

        verifyNoInteractions(channelProviders);
    }

    @Test
    @DisplayName("another event type on the same topic is ignored")
    void otherEventTypesAreIgnored() {
        listener.onTenantEvent("""
                {"eventType":"TENANT_CREATED","tenantId":7,"stateCode":"MP"}
                """);
        listener.onTenantEvent("""
                {"eventType":"TENANT_LOCATION_HIERARCHY_UPDATED","tenantId":7,
                 "configKeys":["EMAIL_PROVIDER_SETTINGS"]}
                """);

        verifyNoInteractions(channelProviders);
    }

    @Test
    @DisplayName("an event with no usable tenantId is ignored")
    void missingTenantIdIsIgnored() {
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","configKeys":["EMAIL_PROVIDER_SETTINGS"]}
                """);
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":null,
                 "configKeys":["EMAIL_PROVIDER_SETTINGS"]}
                """);
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":"seven",
                 "configKeys":["EMAIL_PROVIDER_SETTINGS"]}
                """);

        verifyNoInteractions(channelProviders);
    }

    @Test
    @DisplayName("a missing, null or non-array configKeys evicts nothing")
    void malformedConfigKeysEvictNothing() {
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":7,"stateCode":"MP"}
                """);
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":7,"configKeys":null}
                """);
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":7,
                 "configKeys":"EMAIL_PROVIDER_SETTINGS"}
                """);
        listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":7,"configKeys":[]}
                """);

        verifyNoInteractions(channelProviders);
    }

    @Test
    @DisplayName("unparseable JSON is swallowed, not rethrown")
    void unparseableJsonIsSwallowed() {
        // There is no retry and no dead-letter topic for this listener, so an exception escaping
        // would only make the container redeliver the same record forever.
        assertThatCode(() -> listener.onTenantEvent("not json at all")).doesNotThrowAnyException();
        assertThatCode(() -> listener.onTenantEvent("")).doesNotThrowAnyException();
        assertThatCode(() -> listener.onTenantEvent("{\"eventType\":")).doesNotThrowAnyException();

        verifyNoInteractions(channelProviders);
    }

    @Test
    @DisplayName("an eviction that throws does not escape the listener")
    void evictionFailureDoesNotEscape() {
        org.mockito.Mockito.doThrow(new IllegalStateException("cache is gone"))
                .when(channelProviders).evict(7, MessagingChannel.EMAIL);

        assertThatCode(() -> listener.onTenantEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":7,
                 "configKeys":["EMAIL_PROVIDER_SETTINGS"]}
                """)).doesNotThrowAnyException();
    }
}
