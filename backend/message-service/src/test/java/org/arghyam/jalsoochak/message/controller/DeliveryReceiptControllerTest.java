package org.arghyam.jalsoochak.message.controller;

import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceipt;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceiptAdapter;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceiptRequest;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryState;
import org.arghyam.jalsoochak.message.channel.provider.ReceiptRejectedException;
import org.arghyam.jalsoochak.message.ledger.NotificationLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The provider-neutral receipt endpoint: routing by provider id, the raw body untouched, and its answers. */
@ExtendWith(MockitoExtension.class)
class DeliveryReceiptControllerTest {

    @Mock private DeliveryReceiptAdapter adapter;
    @Mock private NotificationLedger ledger;

    private DeliveryReceiptController controller;

    @BeforeEach
    void setUp() {
        lenient().when(adapter.providerId()).thenReturn("sendgrid");
        controller = new DeliveryReceiptController(List.of(adapter), ledger);
    }

    private static MockHttpServletRequest request(String body, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/message/delivery-receipts/sendgrid");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setQueryString(query);
        request.addHeader("X-Twilio-Email-Event-Webhook-Signature", "sig");
        return request;
    }

    @Test
    void handsTheAdapterTheExactBody_andAppliesEveryReport() throws Exception {
        DeliveryReceipt receipt = new DeliveryReceipt("sendgrid", "m1", null, DeliveryState.DELIVERED, "delivered",
                null, null, null, null, null);
        when(adapter.parseAndVerify(any())).thenReturn(List.of(receipt, receipt));
        when(ledger.applyReceipt(receipt)).thenReturn(1);

        ResponseEntity<Map<String, Object>> response =
                controller.receive("SendGrid", request("[{\"event\":\"delivered\"}]", "token=a%20b&ref=x"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("received", 2).containsEntry("applied", 2);
        ArgumentCaptor<DeliveryReceiptRequest> passed = ArgumentCaptor.forClass(DeliveryReceiptRequest.class);
        verify(adapter).parseAndVerify(passed.capture());
        assertThat(new String(passed.getValue().body(), StandardCharsets.UTF_8)).isEqualTo("[{\"event\":\"delivered\"}]");
        assertThat(passed.getValue().header("X-TWILIO-EMAIL-EVENT-WEBHOOK-SIGNATURE")).isEqualTo("sig");
        assertThat(passed.getValue().query()).containsEntry("token", "a b").containsEntry("ref", "x");
    }

    @Test
    void anUnknownProviderIs404() throws Exception {
        assertThat(controller.receive("pigeon", request("", null)).getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(ledger);
    }

    @Test
    void anUnauthenticatedRequestIs401_andTouchesNothing() throws Exception {
        when(adapter.parseAndVerify(any())).thenThrow(new ReceiptRejectedException("bad signature"));

        assertThat(controller.receive("sendgrid", request("[]", null)).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(ledger);
    }

    @Test
    void anUnreadableRequestIs400() throws Exception {
        when(adapter.parseAndVerify(any())).thenThrow(new IllegalArgumentException("not json"));

        assertThat(controller.receive("sendgrid", request("x", null)).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void anOversizedBodyIsRefusedUnread() throws Exception {
        MockHttpServletRequest huge = request("x".repeat(2 * 1024 * 1024 + 1), null);

        assertThat(controller.receive("sendgrid", huge).getStatusCode().value()).isEqualTo(413);
        verifyNoInteractions(ledger);
    }
}
