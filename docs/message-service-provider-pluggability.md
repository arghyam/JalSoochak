# Message Service — Provider Pluggability (Email & SMS)

**Scope:** how JalSoochak's `message-service` delivers transactional email and SMS without being
locked to any single vendor, and how the delivery medium for a login OTP is chosen at runtime.

---

## 1. Why this design exists

Transactional messaging is the part of a platform most exposed to forces outside our control:

- **Commercial** — vendor pricing changes, contracts lapse, a state mandates a different empanelled provider.
- **Regulatory** — Indian SMS requires DLT/TRAI registration (entity ID, header ID, template ID) that is
  specific to each provider and each approved message text.
- **Operational** — sandbox credentials in staging, production credentials in production, and the ability
  to fall back quickly when a provider has an outage.

None of these should require touching business logic, re-testing message content, or shipping a new build.

The design goal is therefore precise:

> **Changing the email or SMS provider must be a configuration change, not a code change.**

---

## 2. The pattern — Ports and Adapters

The service follows the **Ports & Adapters** (hexagonal) architecture for both email and SMS:

- A **port** is a small interface that *we* own, expressed purely in business terms.
- An **adapter** is a vendor-specific implementation of that port.
- Business code depends **only** on the port. It never names a vendor.
- Spring selects exactly one adapter at startup, driven by a configuration property.

```
                       ┌────────────────────────────┐
   Business logic ───▶ │  Port  (interface we own)  │
   (provider-agnostic) └────────────┬───────────────┘
                                    │  exactly one adapter is
                                    │  activated at startup
                 ┌──────────────────┼──────────────────┐
                 ▼                  ▼                  ▼
          ┌────────────┐    ┌────────────┐    ┌────────────────┐
          │ Adapter A  │    │ Adapter B  │    │  Adapter C     │
          │ (SendGrid) │    │  (SMTP)    │    │ (future vendor)│
          └────────────┘    └────────────┘    └────────────────┘
```

---

## 3. Email

### 3.1 The port

`channel/EmailSender.java`

```java
public interface EmailSender {

    /**
     * Send a transactional email.
     *
     * @throws RuntimeException if delivery fails for any reason
     */
    void send(MailRequest request);
}
```

Four deliberate properties of this interface:

| Property | Why it matters |
| --- | --- |
| **One method** | No vendor vocabulary leaks in — no API keys, no `MimeMessage`, no template IDs. |
| **Domain parameter** | `MailRequest` is a type we own, not a SendGrid or JavaMail type. |
| **Failure contract in the interface** | It documents that failures *throw*, so callers can route the message to a dead-letter topic. |
| **Self-documenting** | The Javadoc names the property that selects the implementation. |

### 3.2 The provider-neutral payload

`dto/MailRequest.java` and `dto/MailTemplate.java`

```java
public record MailRequest(
        String to,
        MailTemplate template,
        Map<String, Object> templateVariables
) { /* canonical constructor defensively copies the map — immutable once built */ }

public enum MailTemplate {
    PASSWORD_RESET,
    REINVITATION,
    DEFAULT_INVITATION,
    SUPER_USER_INVITATION,
    STATE_ADMIN_INVITATION
}
```

This is the **anti-corruption layer**, and it is the single most important decision in the design.

`MailTemplate` is a *business* concept — "this message is a password reset". It is **not** a vendor
concept — it is not "SendGrid dynamic template `d-9d83de36…`". Translating the business concept into
whatever a particular vendor understands is the adapter's job, and only the adapter's job.

That is precisely why swapping providers costs nothing upstream.

### 3.3 Adapter A — SendGrid (default)

`channel/SendGridMailSender.java`

```java
@Component
@ConditionalOnProperty(name = "notification.mail.provider",
                       havingValue = "sendgrid", matchIfMissing = true)
public class SendGridMailSender implements EmailSender { … }
```

It resolves the business template into a **SendGrid dynamic template ID**:

```java
private String resolveTemplateId(MailTemplate template) {
    MailProperties.Templates t = mailProperties.sendgrid().templates();
    return switch (template) {
        case PASSWORD_RESET         -> t.passwordReset();
        case REINVITATION           -> t.reinvitation();
        case DEFAULT_INVITATION     -> t.defaultInvitation();
        case SUPER_USER_INVITATION  -> t.superUserInvitation();
        case STATE_ADMIN_INVITATION -> t.stateAdminInvitation();
    };
}
```

> **Compiler-enforced completeness.** This is an exhaustive `switch` over an enum with no `default`
> branch. Adding a sixth mail template becomes a **compile error in every adapter** until each one
> handles it. Adapters cannot silently fall out of sync.

Branding is injected by the adapter from configuration, never taken from the incoming message:

```java
dynamicData.put("logo_image", mailProperties.logoImageUrl());
```

The logo is a deployment concern, not a message concern.

### 3.4 Adapter B — SMTP

`channel/SmtpMailSender.java`

```java
@Component
@ConditionalOnProperty(name = "notification.mail.provider", havingValue = "smtp")
public class SmtpMailSender implements EmailSender { … }
```

The **same enum**, resolved completely differently — into a subject/body pair held in `application.yml`,
with `{placeholder}` interpolation, delivered through Spring's `JavaMailSender`:

```java
MailProperties.SmtpTemplate tmpl = resolveTemplate(request.template());

String subject = interpolate(tmpl.subject(), vars);
String body    = interpolate(tmpl.body(), vars);

javaMailSender.send(message);
```

All five message bodies are already written out in `application.yml`, so switching to SMTP requires
**no code and no external template setup** — the content ships with the service.

### 3.5 The consumer — provider-agnostic by construction

`service/AccountEmailService.java`

```java
@Service
@RequiredArgsConstructor
public class AccountEmailService {

    private final EmailSender mailSender;   // ← the port. Not SendGrid. Not SMTP.

    public void sendPasswordResetEmail(String to, String resetLink, int expiryMinutes) {
        mailSender.send(new MailRequest(to, MailTemplate.PASSWORD_RESET,
                Map.of("reset_link", resetLink, "expiry_minutes", expiryMinutes)));
    }
}
```

**The verifiable claim:** search the entire service and the class names `SendGridMailSender` and
`SmtpMailSender` appear **nowhere** outside their own source file and their own test file.

That is the proof the abstraction actually holds, rather than merely being described.

---

## 4. SMS

The SMS layer was refactored to mirror the email abstraction exactly (PR #439). Email proved the
pattern; SMS then adopted it as a pure refactor with no behavioural change.

### 4.1 The port

`channel/SmsSender.java`

```java
public interface SmsSender {

    Mono<Boolean> sendOtp(String phoneNumber, String otp, int expiryMinutes);
}
```

Two respects in which this contract is richer than email's:

**It is reactive.** The Kafka listener thread is never blocked waiting on the SMS provider.

**It encodes retry policy in three states**, not two:

| Signal | Meaning | Caller behaviour |
| --- | --- | --- |
| emits `true` | Provider accepted the message | Done |
| emits `false` | **Non-retryable** rejection (4xx, bad configuration) | Must **not** trigger a retry |
| error signal | **Transient** failure (5xx, network, timeout) | May be retried |

This is architecturally significant: because the distinction lives in the *port*, every future SMS
adapter is **obliged to classify its own errors correctly**. The caller is never left guessing retry
semantics from an exception type it does not recognise.

### 4.2 The adapter — SMSCountry (default)

`channel/SmsCountryService.java`

```java
@Service
@ConditionalOnProperty(name = "notification.sms.provider",
                       havingValue = "smscountry", matchIfMissing = true)
public class SmsCountryService implements SmsSender { … }
```

Everything vendor-specific is sealed inside this one class:

- HTTP Basic authentication and Base64 credential encoding
- The `POST {baseUrl}/Accounts/{authKey}/SMSes/` endpoint shape
- Response parsing of the `Success` / `ApiId` / `MessageUUID` fields
- **DLT / TRAI compliance fields** — `DLTTemplateId`, `PrincipalEntityId`, `DLTHeaderId`
- The DLT-approved message text itself

The DLT point deserves emphasis for an Indian deployment: a different provider means a *different DLT
registration and a different approved template*. None of that regulatory detail reaches the caller.

The error classification required by the port is implemented explicitly:

```java
.onErrorResume(WebClientResponseException.class, e -> {
    if (e.getStatusCode().is5xxServerError()) {
        return Mono.error(new RuntimeException("SMSCountry OTP send failed (server error)", e));  // transient
    }
    return Mono.just(false);   // 4xx — configuration/auth problem, non-retryable
})
```

A dry-run guard (`notifications.sms.dry-run`) suppresses live delivery for non-production environments
without removing credentials.

### 4.3 The consumer

`service/NotificationEventRouter.java`

```java
private final SmsSender smsSender;      // ← the port, not the vendor class
```

Changing this single field from the concrete class to the port was the entire substance of the
refactor. Adding a future SMS provider now touches no caller at all.

---

## 5. Selecting the provider

`application.yml`

```yaml
notification:
  sms:
    provider: ${SMS_PROVIDER:smscountry}

  mail:
    provider: ${MAIL_PROVIDER:sendgrid}
```

**Switching provider is one environment variable and a restart.**

- `MAIL_PROVIDER=smtp` — the entire email path changes vendor.
- `SMS_PROVIDER=<name>` — the entire SMS path changes vendor.

No rebuild. No code change. No separate artifact. Because all adapters ship in the same build,
rollback is equally cheap: flip the variable back and restart.

---

## 6. Configuration is typed, validated, and fails fast

`config/MailProperties.java`

```java
@ConfigurationProperties(prefix = "notification.mail")
@Validated
public record MailProperties(
        String provider,
        String fromAddress,
        String fromName,
        String logoImageUrl,
        @Valid SendGrid sendgrid,
        @Valid Smtp smtp
) {
    public record SendGrid(@NotBlank String apiKey, @Valid Templates templates) {}
    public record Templates(@NotBlank String passwordReset, /* … */) {}
    public record Smtp(SmtpTemplates templates) {}
    public record SmtpTemplate(@NotBlank String subject, @NotBlank String body) {}
}
```

Both provider blocks live under one prefix; the inactive one is simply absent. Each adapter then
validates *its own* configuration in its constructor, with actionable messages:

```
"Missing SendGrid API key: set SENDGRID_API_KEY environment variable when provider=sendgrid"
```

**The service refuses to start with incomplete provider configuration.** A missing API key or template
ID surfaces at deployment time — not at 2 a.m. when the first password-reset email is attempted.

---

## 7. Runtime medium selection — the `deliveryChannel` field

Provider selection answers *"which vendor delivers our SMS?"*. A separate question is
*"should this particular message go out over SMS at all, or over WhatsApp?"* — and that is decided
per message, at runtime.

### 7.1 Entry point

All notification traffic arrives on a single Kafka topic and is dispatched by event type:

`kafka/KafkaConsumer.java`

```java
@KafkaListener(topics = "common-topic", groupId = "${spring.kafka.consumer.group-id}")
public void consume(String message) {
    notificationEventRouter.route(message);
}
```

`service/NotificationEventRouter.java`

```java
switch (eventType.toUpperCase()) {
    case "SEND_LOGIN_OTP"            -> handleSendLoginOtp(root);
    case "SEND_INVITE_EMAIL"         -> handleInviteEmail(root);
    case "SEND_REINVITE_EMAIL"       -> handleReinviteEmail(root);
    case "SEND_PASSWORD_RESET_EMAIL" -> handlePasswordResetEmail(root);
    // … other notification event types …
    default -> log.warn("Unknown eventType '{}', ignoring message", eventType);
}
```

The `default` branch **skips** rather than retries: an unrecognised event type is a permanent
condition, and retrying it would loop forever.

### 7.2 The login OTP — two axes of choice, composed

The medium is chosen by configuration in **`user-service`**, which stamps it onto the event:

`user-service/application.yml`

```yaml
otp:
  delivery-channel: ${OTP_DELIVERY_CHANNEL:WHATSAPP}
```

`message-service` then honours that field:

```java
if ("SMS".equals(deliveryChannel)) {
    smsSender.sendOtp(phone, otp, expiryMinutes)      // ← the port; vendor unknown here
             …
             .subscribe();

} else if ("WHATSAPP".equals(deliveryChannel)) {
    whatsAppChannel.sendLoginOtp(contactId, otp);

} else {
    log.error("Unsupported deliveryChannel '{}', must be 'SMS' or 'WHATSAPP', skipping", deliveryChannel);
}
```

**Two independent axes compose cleanly:**

```
   WHICH MEDIUM?                          WHICH VENDOR FOR THAT MEDIUM?
   OTP_DELIVERY_CHANNEL                   SMS_PROVIDER  /  MAIL_PROVIDER
   (set in user-service)                  (set in message-service)
            │                                        │
            └────────────────┬───────────────────────┘
                             ▼
              Neither requires a code change
```

A state that prefers SMS OTPs sets `OTP_DELIVERY_CHANNEL=SMS`. If that state's empanelled SMS vendor
differs, `SMS_PROVIDER` selects the matching adapter. The two decisions are independent and neither
touches business logic.

### 7.3 Failure handling is chosen by idempotency

Retry behaviour is not uniform. Each event type gets the strategy its semantics demand:

| Event | Strategy | Rationale |
| --- | --- | --- |
| `SEND_INVITE_EMAIL`<br>`SEND_REINVITE_EMAIL`<br>`SEND_PASSWORD_RESET_EMAIL` | Publish to dead-letter topic `account-email-dlt`; **do not** rethrow | Email delivery is **not idempotent**. Rethrowing would trigger Kafka retry and send duplicate emails to recipients who already received theirs. |
| `SEND_LOGIN_OTP` (SMS) | Log and **swallow** transient errors | OTPs are **time-sensitive**. A delayed retry delivers an OTP that has already expired — worse than no message at all. |
| Idempotent operations | Rethrow → Kafka retry with back-off | Safe to repeat, so the platform's standard retry policy applies. |

The OTP case, made explicit in code:

```java
.onErrorResume(e -> {
    // Swallow retryable errors to prevent Kafka retries of the entire event
    // (OTP sends are time-sensitive; a delayed retry would deliver an expired OTP)
    return Mono.empty();
})
```

Failed email records on `account-email-dlt` are **failure notices, not replayable commands**. The
record deliberately omits the invite or reset link: those are single-use bearer credentials, and this
topic is long-retention and consumed by nothing. They would also be expired by the time anyone read
them — invites in hours, resets in minutes. Recovery is therefore to **re-issue** the invite or reset
from user-service, which mints a fresh token. Each record carries:

- a **deterministic `failureId`**, derived from the business identity, so repeated failures for the
  same recipient produce the same ID and can be deduplicated — a dedupe key for the failure record,
  not a replay token;
- the **`originalEventType`** and **`recipientRole`**, so an operator knows what to re-issue;
- `to`, so they know to whom;
- `failedAt` and `errorReason` for operational triage.

The service deliberately **does not consume its own dead-letter topic** — doing so from the same
service that produces to it would create an unbounded retry loop. Recovery is an explicit, monitored
operations action, driven by the alerts in `backend/logger/prometheus/alerts.yml`.

Records that *are* replayable land elsewhere: a `TransientMailException` is rethrown, the container
retries, and an exhausted record goes to `common-topic.DLT` with the original event copied whole —
link included. Nothing reached the recipient in those cases, so a replay cannot duplicate an email.

---

## 8. Adding a new provider

Illustrated with a hypothetical new SMS vendor.

**Step 1 — write the adapter** (a single new file, roughly 80 lines):

```java
@Service
@ConditionalOnProperty(name = "notification.sms.provider", havingValue = "newvendor")
public class NewVendorSmsService implements SmsSender {

    @Override
    public Mono<Boolean> sendOtp(String phoneNumber, String otp, int expiryMinutes) {
        // vendor-specific auth, request shape, response parsing, DLT registration
    }
}
```

**Step 2 — add its configuration block** to `application.yml`.

**Step 3 — add its adapter test** (WireMock against the vendor's HTTP contract).

**Step 4 — deploy** with `SMS_PROVIDER=newvendor`.

| Category | Files changed |
| --- | --- |
| New adapter | 1 (new file) |
| New adapter test | 1 (new file) |
| Configuration | 1 YAML block |
| **Business logic** | **0** |
| **Existing tests** | **0** |

`NotificationEventRouter` never learns the new vendor exists.

### How this shows up in the test suite

The abstraction pays for itself directly in test economics:

- **Use-case tests mock the port.** `AccountEmailServiceTest` mocks `EmailSender` and asserts on the
  captured `MailRequest` — verifying template selection and variables with no vendor present anywhere
  in the test. It does not change when a provider is added.
- **Adapter tests are vendor-specific.** `SmtpMailSenderTest` mocks `JavaMailSender`;
  `SendGridMailSenderTest` and `SmsCountryServiceTest` run **WireMock** against the real HTTP contract.

The result: *N* providers means *N* adapter test classes plus **one unchanged** business-logic test
class. Test cost scales linearly with providers, and business-logic tests stay stable across vendor
changes.

---

## 9. Summary

| Question | Answer |
| --- | --- |
| Which vendor sends our email? | `MAIL_PROVIDER` — configuration, decided at startup |
| Which vendor sends our SMS? | `SMS_PROVIDER` — configuration, decided at startup |
| Which medium carries a login OTP? | `deliveryChannel` on the event — decided per message |
| Cost of changing vendor | One environment variable and a restart |
| Cost of adding a vendor | One adapter class, one test class, one YAML block |
| Business logic affected | None — it depends only on `EmailSender` / `SmsSender` |
| Misconfiguration detected | At startup, with an actionable message — never mid-delivery |

**In one sentence:** business logic depends on interfaces we own and control, vendor detail is confined
to interchangeable adapters, and the choice between them is made by configuration — so replacing a
provider is an operational decision rather than an engineering project.
