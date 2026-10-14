package com.smartcart.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window, per-IP rate limiter. Credential endpoints get a much tighter budget to slow
 * brute-force attempts. For multi-replica deployments put an API gateway / ingress limit (or a
 * Redis-backed Bucket4j store) in front, since this state is per-instance.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_MS = 60_000;

    private final int authLimit;
    private final int generalLimit;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(AppProperties props) {
        this.authLimit = props.rateLimit().authPerMinute();
        this.generalLimit = props.rateLimit().generalPerMinute();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || uri.startsWith("/api/webhooks/") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String uri = req.getRequestURI();
        boolean credential = "POST".equals(req.getMethod())
                && (uri.equals("/api/auth/login") || uri.equals("/api/auth/register"));
        String key = (credential ? "auth:" : "api:") + req.getRemoteAddr();
        int limit = credential ? authLimit : generalLimit;

        long now = System.currentTimeMillis();
        Window w = windows.compute(key, (k, cur) ->
                (cur == null || now - cur.start >= WINDOW_MS) ? new Window(now) : cur);
        if (w.count.incrementAndGet() > limit) {
            long retryAfter = Math.max(1, (WINDOW_MS - (now - w.start)) / 1000);
            res.setStatus(429);
            res.setHeader("Retry-After", String.valueOf(retryAfter));
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            res.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\",\"detail\":\"Rate limit exceeded, retry in "
                    + retryAfter + "s\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    @Scheduled(fixedDelay = 120_000)
    public void evictStale() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().start >= WINDOW_MS);
    }

    private static final class Window {
        final long start;
        final AtomicInteger count = new AtomicInteger();

        Window(long start) {
            this.start = start;
        }
    }
}
