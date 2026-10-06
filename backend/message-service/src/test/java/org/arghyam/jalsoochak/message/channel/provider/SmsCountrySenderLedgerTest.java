package org.arghyam.jalsoochak.message.channel.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.arghyam.jalsoochak.message.dto.SmsProviderSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link SmsCountrySender} hands the delivery ledger: the provider's id and verdict at send time,
 * the delivery-report callback it asks for, and the status it reads back. WireMock stands in for
 * SMSCountry; the response bodies are the shapes its API returned when probed, with the number and the
 * OTP text replaced by fabricated values.
 */
class SmsCountrySenderLedgerTest {

    private static final String AUTH_KEY = "testAuthKey";
    private static final String SMS_PATH = "/v0.1/Accounts/" + AUTH_KEY + "/SMSes/";
    private static final String UUID = "574cd18e-7ab7-4785-a388-d8443eba59b8";

    private WireMockServer wireMock;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    private SmsCountrySender sender(String deliveryReportUrl) {
        return new SmsCountrySender(WebClient.builder(), new SmsCountrySettings(
                wireMock.baseUrl() + "/v0.1", AUTH_KEY, "token", "JALSHK", "pe", "tpl", "hdr",
                SmsProviderSettings.SmsCountry.DEFAULT_OTP_TEMPLATE), false, deliveryReportUrl);
    }

    @Test
    void anAcceptedSendCarriesTheMessageUuid() {
        wireMock.stubFor(post(urlEqualTo(SMS_PATH)).willReturn(json(200,
                "{\"ApiId\":\"api-1\",\"Success\":\"True\",\"Message\":\"SMS Queued\",\"MessageUUID\":\"" + UUID + "\"}")));

        SmsSendResult result = sender(null).sendOtpForResult("91XXXXXXXXXX", "123456", 5, "tenant_test:ref").block();

        assertThat(result.accepted()).isTrue();
        assertThat(result.acceptance().providerMessageId()).isEqualTo(UUID);
        assertThat(result.acceptance().providerStatus()).isEqualTo("SMS Queued");
        assertThat(sender(null).providerId()).isEqualTo("smscountry");
    }

    @Test
    void aRefusalIsAValueCarryingTheProvidersCode() {
        wireMock.stubFor(post(urlEqualTo(SMS_PATH)).willReturn(json(200,
                "{\"ApiId\":\"api-2\",\"Success\":false,\"Message\":\"Invalid number\"}")));

        SmsSendResult result = sender(null).sendOtpForResult("91XXXXXXXXXX", "123456", 5).block();

        assertThat(result.accepted()).isFalse();
        assertThat(result.errorCode()).isEqualTo("api-2");
        assertThat(result.errorMessage()).isEqualTo("Invalid number");
    }

    @Test
    void aClientErrorIsARefusal_butAServerErrorStaysAnErrorSignal() {
        wireMock.stubFor(post(urlEqualTo(SMS_PATH)).willReturn(aResponse().withStatus(401)));
        assertThat(sender(null).sendOtpForResult("91XXXXXXXXXX", "1", 5).block().errorCode()).isEqualTo("401");

        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(SMS_PATH)).willReturn(aResponse().withStatus(503)));
        // The tri-state contract: a transient failure is an error, which the router swallows so an
        // expired OTP is never re-sent.
        assertThatThrownBy(() -> sender(null).sendOtpForResult("91XXXXXXXXXX", "1", 5).block())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void asksForADeliveryReportCarryingTheTrackingRef_onlyWhenConfigured() {
        wireMock.stubFor(post(urlEqualTo(SMS_PATH)).willReturn(json(200,
                "{\"Success\":true,\"MessageUUID\":\"" + UUID + "\"}")));

        sender("https://host/api/v1/message/delivery-receipts/smscountry?token=s3cret")
                .sendOtpForResult("91XXXXXXXXXX", "1", 5, "tenant_test:abc").block();
        wireMock.verify(postRequestedFor(urlEqualTo(SMS_PATH))
                .withRequestBody(containing("\"DRNotifyUrl\""))
                .withRequestBody(containing("token=s3cret"))
                .withRequestBody(containing("ref=tenant_test:abc"))
                .withRequestBody(containing("\"DRNotifyHttpMethod\":\"POST\"")));

        wireMock.resetRequests();
        sender(null).sendOtpForResult("91XXXXXXXXXX", "1", 5, "tenant_test:abc").block();
        wireMock.verify(postRequestedFor(urlEqualTo(SMS_PATH)).withRequestBody(notContaining("DRNotifyUrl")));
    }

    @Test
    void readsStatusTimeAndCostButNothingElseFromADetailRecord() {
        wireMock.stubFor(get(urlEqualTo(SMS_PATH + UUID + "/")).willReturn(json(200, """
                {"ApiId":"x","Success":"True","Message":"Action completed","SMS":{
                  "ToolName":"API","Number":"91XXXXXXXXXX","MessageUUID":"%s",
                  "Text":"Your OTP for Jalsoochak login is 000000.","SenderId":"JALSHK","Cost":"0.3 INR",
                  "ProcessTime":"Sep 29 2026  8:01PM","Status":"Delivered","StatusTime":"Sep 29 2026  8:02PM"}}
                """.formatted(UUID))));

        List<DeliveryReceipt> receipts = sender(null).lookupStatuses(List.of(UUID), Instant.now(), Instant.now());

        assertThat(receipts).singleElement().satisfies(r -> {
            assertThat(r.providerMessageId()).isEqualTo(UUID);
            assertThat(r.state()).isEqualTo(DeliveryState.DELIVERED);
            assertThat(r.providerStatus()).isEqualTo("Delivered");
            assertThat(r.cost()).isEqualByComparingTo("0.3");
            assertThat(r.costCurrency()).isEqualTo("INR");
            // 8:02 PM IST
            assertThat(r.occurredAt()).isEqualTo(Instant.parse("2026-09-29T14:32:00Z"));
            assertThat(r.toString()).doesNotContain("OTP").doesNotContain("91XXXXXXXXXX");
        });
    }

    @Test
    void readsTheMatchingEntryOfAListResponse_andSkipsIdsThatAreNotUuids() {
        wireMock.stubFor(get(urlEqualTo(SMS_PATH + UUID + "/")).willReturn(json(200, """
                {"SMSes":[{"MessageUUID":"other"},{"MessageUUID":"%s","Status":"Failed"}]}
                """.formatted(UUID))));

        assertThat(sender(null).lookupStatuses(List.of(UUID, "../../x"), Instant.now(), Instant.now()))
                .singleElement().extracting(DeliveryReceipt::state).isEqualTo(DeliveryState.FAILED);
    }

    @Test
    void aFailedLookupReturnsWhatItHas() {
        wireMock.stubFor(get(urlEqualTo(SMS_PATH + UUID + "/")).willReturn(aResponse().withStatus(500)));

        assertThat(sender(null).lookupStatuses(List.of(UUID), Instant.now(), Instant.now())).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "Delivered, DELIVERED", "DELIVRD, DELIVERED", "Failed, FAILED", "Undelivered, FAILED",
            "Rejected, FAILED", "Expired, FAILED", "Sent, PENDING", "Submitted, PENDING",
            "SomethingNew, PENDING"})
    void mapsTheProvidersWordsUnknownOnesToPending(String word, DeliveryState expected) {
        assertThat(SmsCountrySender.mapStatus(word)).isEqualTo(expected);
    }

    @Test
    void anUnreadableTimeIsLeftOut() {
        assertThat(SmsCountrySender.parseTime("yesterday")).isNull();
        assertThat(SmsCountrySender.parseTime(null)).isNull();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }
}
