package org.arghyam.jalsoochak.telemetry.controller;

import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.LocationReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedChannelRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.service.GlificWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlificWebhookControllerUnitTest {

    @Test
    void languageSelectionReturnsOkWhenServiceSucceeds() {
        GlificWebhookService service = new StubGlificWebhookService(false, false);
        GlificWebhookController controller = new GlificWebhookController(service);

        ResponseEntity<IntroResponse> response = controller.languageSelection(
                IntroRequest.builder().contactId("919999999999").build()
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().isSuccess());
        assertEquals("language-ok", response.getBody().getMessage());
    }

    @Test
    void languageSelectionReturns500WhenServiceThrows() {
        GlificWebhookService service = new StubGlificWebhookService(true, false);
        GlificWebhookController controller = new GlificWebhookController(service);

        ResponseEntity<IntroResponse> response = controller.languageSelection(
                IntroRequest.builder().contactId("919999999999").build()
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().isSuccess());
    }

    @Test
    void selectedChannelReturns500WhenServiceThrows() {
        GlificWebhookService service = new StubGlificWebhookService(false, true);
        GlificWebhookController controller = new GlificWebhookController(service);

        ResponseEntity<IntroResponse> response = controller.selectedChannel(
                SelectedChannelRequest.builder().contactId("919999999999").channel("1").build()
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().isSuccess());
    }

    @Test
    void locationReturnsOkWhenServiceSucceeds() {
        GlificWebhookService service = new StubGlificWebhookService(false, false);
        GlificWebhookController controller = new GlificWebhookController(service);

        ResponseEntity<CreateReadingResponse> response = controller.location(
                LocationReadingRequest.builder()
                        .contact(LocationReadingRequest.Contact.builder().phone("919999999999").build())
                        .latitude(BigDecimal.valueOf(12.34))
                        .longitude(BigDecimal.valueOf(56.78))
                        .build()
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().isSuccess());
        assertEquals("location-ok", response.getBody().getMessage());
    }

    private static final class StubGlificWebhookService extends GlificWebhookService {
        private final boolean throwLanguageSelection;
        private final boolean throwSelectedChannel;

        private StubGlificWebhookService(boolean throwLanguageSelection, boolean throwSelectedChannel) {
            super(null, null, null, null);
            this.throwLanguageSelection = throwLanguageSelection;
            this.throwSelectedChannel = throwSelectedChannel;
        }

        @Override
        public IntroResponse languageSelectionMessage(IntroRequest request) {
            if (throwLanguageSelection) {
                throw new IllegalStateException("boom");
            }
            return IntroResponse.builder().success(true).message("language-ok").build();
        }

        @Override
        public IntroResponse selectedChannelMessage(SelectedChannelRequest request) {
            if (throwSelectedChannel) {
                throw new IllegalStateException("boom");
            }
            return IntroResponse.builder().success(true).message("channel-ok").build();
        }

        @Override
        public CreateReadingResponse locationReadingMessage(LocationReadingRequest request) {
            return CreateReadingResponse.builder()
                    .success(true)
                    .message("location-ok")
                    .build();
        }
    }
}
