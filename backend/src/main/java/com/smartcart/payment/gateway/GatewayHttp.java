package com.smartcart.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Minimal JSON-over-HTTPS client shared by the gateway integrations (no vendor SDKs needed). */
@Component
public class GatewayHttp {
    private static final Logger log = LoggerFactory.getLogger(GatewayHttp.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient client;
    private final ObjectMapper mapper;

    public GatewayHttp(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public JsonNode postJson(String gateway, String url, Map<String, String> headers, Object body) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(b::header);
        return execute(gateway, b.build());
    }

    public JsonNode postForm(String gateway, String url, String formBody) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody)).build();
        return execute(gateway, req);
    }

    public JsonNode get(String gateway, String url, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Accept", "application/json").GET();
        headers.forEach(b::header);
        return execute(gateway, b.build());
    }

    private JsonNode execute(String gateway, HttpRequest req) {
        HttpResponse<String> res;
        try {
            res = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("{} API {} {} unreachable: {}", gateway, req.method(), req.uri().getPath(), e.toString());
            throw new GatewayException(gateway, 0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(gateway, 0, null);
        }
        String body = res.body() == null ? "" : res.body();
        if (res.statusCode() / 100 != 2) {
            String description = describe(body);
            // Status and gateway error text only; request bodies and credentials are never logged.
            log.warn("{} API {} {} -> HTTP {}: {}", gateway, req.method(), req.uri().getPath(), res.statusCode(),
                    description);
            throw new GatewayException(gateway, res.statusCode(), description);
        }
        try {
            return body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (IOException e) {
            log.warn("{} API returned non-JSON body for {}", gateway, req.uri().getPath());
            throw new GatewayException(gateway, 0, "invalid response");
        }
    }

    /** Extracts Razorpay-style {"error":{"description"}} or PhonePe-style {"message"} error text. */
    private String describe(String body) {
        try {
            JsonNode n = mapper.readTree(body);
            String d = n.path("error").path("description").asText(null);
            if (d == null) {
                d = n.path("message").asText(null);
            }
            return d == null ? null : d.substring(0, Math.min(d.length(), 200));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
