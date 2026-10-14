package com.smartcart.payment.gateway;

import com.smartcart.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A gateway API call failed. {@code upstreamStatus} is the gateway's HTTP status (0 when it could
 * not be reached); 4xx means the gateway rejected the request (e.g. unknown payment id), which is
 * never retried, while 5xx / 0 are transient.
 */
public class GatewayException extends ApiException {
    private final int upstreamStatus;
    private final String gatewayDescription;

    public GatewayException(String gateway, int upstreamStatus, String gatewayDescription) {
        super(HttpStatus.BAD_GATEWAY, upstreamStatus == 0
                ? gateway + " is unreachable right now, please try again"
                : gateway + " rejected the request" + (gatewayDescription == null ? "" : ": " + gatewayDescription));
        this.upstreamStatus = upstreamStatus;
        this.gatewayDescription = gatewayDescription;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }

    public String getGatewayDescription() {
        return gatewayDescription;
    }

    public boolean isClientError() {
        return upstreamStatus >= 400 && upstreamStatus < 500;
    }
}
