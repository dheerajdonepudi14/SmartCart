package com.smartcart.catalog;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public live-inventory feed (Server-Sent Events), like quick-commerce apps: every time an order
 * reserves or releases stock, or an admin edits it, all open storefronts see the new count at once.
 */
@Component
public class StockEventBroker {
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int MAX_SUBSCRIBERS = 5_000;

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        if (subscribers.size() >= MAX_SUBSCRIBERS) {
            throw new com.smartcart.common.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Live stock feed is busy, please retry");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        send(emitter, "ready", java.util.Map.of("ok", true));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockChanged(StockChangedEvent event) {
        if (event.items().isEmpty()) {
            return;
        }
        subscribers.forEach(e -> send(e, "stock", event.items()));
    }

    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        for (SseEmitter e : subscribers) {
            try {
                e.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException ex) {
                e.completeWithError(ex);
            }
        }
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }
}
