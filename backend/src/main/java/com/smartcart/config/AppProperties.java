package com.smartcart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendUrl,
        Cors cors,
        Jwt jwt,
        RateLimit rateLimit,
        Shipping shipping,
        Seed seed,
        Payments payments
) {
    public record Cors(List<String> allowedOrigins) {}

    public record Jwt(String secret, long accessTokenMinutes, long refreshTokenDays, boolean cookieSecure) {}

    public record RateLimit(int authPerMinute, int generalPerMinute) {}

    public record Shipping(BigDecimal freeAbove, BigDecimal fee) {}

    public record Seed(boolean enabled, String adminEmail, String adminPassword) {}

    public record Payments(Razorpay razorpay, PhonePe phonepe) {}

    public record Razorpay(String keyId, String keySecret, String webhookSecret, String baseUrl) {
        public boolean configured() {
            return notBlank(keyId) && notBlank(keySecret);
        }
    }

    public record PhonePe(String clientId, String clientSecret, String clientVersion, String environment,
                          String webhookUsername, String webhookPassword) {
        public boolean configured() {
            return notBlank(clientId) && notBlank(clientSecret);
        }

        public boolean production() {
            return "PRODUCTION".equalsIgnoreCase(environment);
        }

        public String authUrl() {
            return production()
                    ? "https://api.phonepe.com/apis/identity-manager/v1/oauth/token"
                    : "https://api-preprod.phonepe.com/apis/pg-sandbox/v1/oauth/token";
        }

        public String pgBaseUrl() {
            return production()
                    ? "https://api.phonepe.com/apis/pg"
                    : "https://api-preprod.phonepe.com/apis/pg-sandbox";
        }
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
