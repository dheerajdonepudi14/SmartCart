package com.smartcart.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real-time order status push over Server-Sent Events. Customers subscribe to one order; admins
 * subscribe to the global feed. State is per-instance: behind multiple replicas, fan out through
 * Redis pub/sub or Postgres LISTEN/NOTIFY (clients also fall back to polling).
 */
@Component
public class OrderEventBroker {
    private static final Logger log = LoggerFactory.getLogger(OrderEventBroker.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, List<SseEmitter>> byOrder = new ConcurrentHashMap<>();
    private final List<SseEmitter> admins = new CopyOnWriteArrayList<>();

    public SseEmitter subscribeOrder(Long orderId, Object initialState) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = byOrder.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> {
            list.remove(emitter);
            if (list.isEmpty()) {
                byOrder.remove(orderId, list);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        send(emitter, "status", initialState);
        return emitter;
    }

    public SseEmitter subscribeAdmin() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        admins.add(emitter);
        emitter.onCompletion(() -> admins.remove(emitter));
        emitter.onTimeout(() -> admins.remove(emitter));
        emitter.onError(e -> admins.remove(emitter));
        send(emitter, "ready", Map.of("ok", true));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(OrderStatusChangedEvent event) {
        List<SseEmitter> list = byOrder.get(event.orderId());
        if (list != null) {
            list.forEach(e -> send(e, "status", event));
        }
        admins.forEach(e -> send(e, "order", event));
        log.debug("Pushed order {} -> {}", event.orderId(), event.status());
    }

    /** Keeps idle connections open through proxies and load balancers. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        byOrder.values().forEach(list -> list.forEach(this::ping));
        admins.forEach(this::ping);
    }

    private void ping(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    int subscriberCount(Long orderId) {
        List<SseEmitter> list = byOrder.get(orderId);
        return list == null ? 0 : list.size();
    }
}
