package com.smartcart.payment;

import jakarta.persistence.*;

import java.time.Instant;

/** Records processed webhook deliveries so gateway retries are handled exactly once. */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentGatewayType gateway;

    @Column(name = "event_id", nullable = false, length = 150)
    private String eventId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    protected WebhookEvent() {}

    public WebhookEvent(PaymentGatewayType gateway, String eventId) {
        this.gateway = gateway;
        this.eventId = eventId;
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
}
