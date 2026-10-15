# ADR-006: A `PaymentProvider` port with Razorpay as one adapter

**Status:** Accepted
**Date:** 2026-10-14
**Supersedes / relates to:** ADR-013

## Context

The project needs to demonstrate real gateway integration, but tests, local development and the
demo must not depend on an external network service being reachable and behaving. The methods also
differ structurally: a gateway has orders, signatures and webhooks; cash has none of those.

## Decision

A port in the `payment` module:

```java
Settlement.Method method();
ProviderOrder initiate(Settlement s);
boolean verifyClientCallback(Map<String, String> payload);
Optional<WebhookEvent> parseWebhook(byte[] rawBody, Map<String, String> headers);
```

Adapters: `RazorpayProvider`, `UpiIntentProvider`, `CashProvider`, `SandboxProvider`. An ArchUnit
rule enforces that only the `payment` module references the Razorpay SDK.

## Alternatives considered

- **Call the Razorpay SDK directly from the settlement service.** Pros: less indirection. Cons:
  nothing can be tested without network access or heavy mocking, and settlement logic takes on the
  shape of one specific gateway.
- **A general-purpose payments abstraction layer.** Over-engineered for four adapters, and the
  lowest common denominator would be so thin as to be meaningless.

## Consequences

- **Positive:** `SandboxProvider` makes the entire settle-up flow demoable and testable with zero
  external dependencies, which is what lets Sprint 5 complete before any gateway work starts.
  Adding or swapping a gateway is additive. Enabling or disabling a method becomes configuration —
  which is precisely the mechanism ADR-013 depends on.
- **Negative:** the port is a lowest common denominator, so gateway-specific concerns (order ids,
  payment ids, signatures) surface on the `Settlement` aggregate as nullable fields.
- **Follow-up:** webhook handling is a template method (verify, de-duplicate, dispatch,
  acknowledge) shared across any future gateway adapter.
