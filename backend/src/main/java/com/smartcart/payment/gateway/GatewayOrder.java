package com.smartcart.payment.gateway;

/** Result of creating a payment at a gateway. */
public record GatewayOrder(String gatewayOrderId, String redirectUrl) {}
