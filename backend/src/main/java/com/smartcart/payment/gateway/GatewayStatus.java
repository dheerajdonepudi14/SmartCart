package com.smartcart.payment.gateway;

/** Normalised view of a gateway-side payment state used for confirmation and reconciliation. */
public record GatewayStatus(State state, String gatewayPaymentId, String reason) {
    public enum State { SUCCEEDED, FAILED, PENDING }

    public static GatewayStatus pending() {
        return new GatewayStatus(State.PENDING, null, null);
    }
}
