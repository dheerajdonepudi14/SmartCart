package com.smartcart;

import com.smartcart.auth.JwtService;
import com.smartcart.common.Crypto;
import com.smartcart.config.AppProperties;
import com.smartcart.order.OrderStatus;
import com.smartcart.payment.gateway.GatewayHttp;
import com.smartcart.payment.gateway.GatewayStatus;
import com.smartcart.payment.gateway.PhonePeGateway;
import com.smartcart.payment.gateway.RazorpayGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcart.user.Role;
import com.smartcart.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnitTests {

    static AppProperties props(String rzpKey, String rzpSecret, String rzpWebhook, String ppUser, String ppPass) {
        return new AppProperties("http://localhost:3000",
                new AppProperties.Cors(List.of("http://localhost:3000")),
                new AppProperties.Jwt("unit-test-secret-unit-test-secret-012345", 15, 7, false),
                new AppProperties.RateLimit(10, 100),
                new AppProperties.Shipping(new BigDecimal("499"), new BigDecimal("49")),
                new AppProperties.Seed(false, null, null),
                new AppProperties.Payments(
                        new AppProperties.Razorpay(rzpKey, rzpSecret, rzpWebhook, "https://api.razorpay.com"),
                        new AppProperties.PhonePe("", "", "1", "SANDBOX", ppUser, ppPass)));
    }

    @Test
    void jwtRoundTripAndTamperDetection() {
        JwtService jwt = new JwtService(props("", "", "", "", ""));
        User u = new User("Asha Rao", "asha@example.com", "x", Role.ROLE_ADMIN);
        ReflectionTestUtils.setField(u, "id", 42L);

        String token = jwt.issueAccessToken(u);
        var principal = jwt.verify(token).orElseThrow();
        assertThat(principal.id()).isEqualTo(42L);
        assertThat(principal.role()).isEqualTo("ROLE_ADMIN");
        assertThat(principal.isAdmin()).isTrue();

        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThat(jwt.verify(tampered)).isEmpty();
        assertThat(jwt.verify("not-a-jwt")).isEmpty();
    }

    @Test
    void jwtRejectsShortSecret() {
        AppProperties p = props("", "", "", "", "");
        AppProperties weak = new AppProperties(p.frontendUrl(), p.cors(), new AppProperties.Jwt("short", 15, 7, false),
                p.rateLimit(), p.shipping(), p.seed(), p.payments());
        assertThatThrownBy(() -> new JwtService(weak)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void razorpayCheckoutSignature() {
        RazorpayGateway rzp = new RazorpayGateway(props("rzp_test_abc", "s3cr3t", "wh", "", ""),
                new GatewayHttp(new ObjectMapper()));
        String sig = Crypto.hmacSha256Hex("s3cr3t", "order_123|pay_456");
        assertThat(rzp.verifyCheckoutSignature("order_123", "pay_456", sig)).isTrue();
        assertThat(rzp.verifyCheckoutSignature("order_123", "pay_999", sig)).isFalse();
        assertThat(rzp.verifyCheckoutSignature("order_123", "pay_456", "deadbeef")).isFalse();
        assertThat(rzp.mode()).isEqualTo("test");
    }

    @Test
    void razorpayWebhookSignature() {
        RazorpayGateway rzp = new RazorpayGateway(props("rzp_live_abc", "s", "whsec", "", ""),
                new GatewayHttp(new ObjectMapper()));
        String body = "{\"event\":\"payment.captured\"}";
        assertThat(rzp.verifyWebhook(body, Crypto.hmacSha256Hex("whsec", body))).isTrue();
        assertThat(rzp.verifyWebhook(body + " ", Crypto.hmacSha256Hex("whsec", body))).isFalse();
        assertThat(rzp.mode()).isEqualTo("live");
    }

    @Test
    void phonePeWebhookAuthorization() {
        PhonePeGateway pp = new PhonePeGateway(props("", "", "", "user", "pass"), new GatewayHttp(new ObjectMapper()));
        String hash = Crypto.sha256Hex("user:pass");
        assertThat(pp.verifyWebhook(hash)).isTrue();
        assertThat(pp.verifyWebhook("SHA256 " + hash)).isTrue();
        assertThat(pp.verifyWebhook(hash.toUpperCase())).isTrue();
        assertThat(pp.verifyWebhook(Crypto.sha256Hex("user:wrong"))).isFalse();
        assertThat(pp.verifyWebhook(null)).isFalse();
    }

    @Test
    void phonePeStatusMapping() throws Exception {
        ObjectMapper m = new ObjectMapper();
        var done = PhonePeGateway.toStatus(m.readTree(
                "{\"state\":\"COMPLETED\",\"paymentDetails\":[{\"transactionId\":\"OM123\",\"state\":\"COMPLETED\"}]}"));
        assertThat(done.state()).isEqualTo(GatewayStatus.State.SUCCEEDED);
        assertThat(done.gatewayPaymentId()).isEqualTo("OM123");
        assertThat(PhonePeGateway.toStatus(m.readTree("{\"state\":\"FAILED\",\"errorCode\":\"TXN_DECLINED\"}")).state())
                .isEqualTo(GatewayStatus.State.FAILED);
        assertThat(PhonePeGateway.toStatus(m.readTree("{\"state\":\"PENDING\"}")).state())
                .isEqualTo(GatewayStatus.State.PENDING);
    }

    @Test
    void orderStatusTransitions() {
        assertThat(OrderStatus.PENDING_PAYMENT.canMoveTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canMoveTo(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canMoveTo(OrderStatus.DELIVERED)).isTrue();
        assertThat(OrderStatus.DELIVERED.canMoveTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.CANCELLED.canMoveTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.PENDING_PAYMENT.canMoveTo(OrderStatus.SHIPPED)).isFalse();
    }
}
