package com.smartcart.job;

import com.smartcart.config.WebSocketConfig;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/** Broadcasts every job status change to admin dashboards subscribed to /topic/jobs. */
@Service
public class JobNotificationService {
    private final SimpMessagingTemplate messaging;

    public JobNotificationService(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void publish(BackgroundJob job) {
        messaging.convertAndSend(WebSocketConfig.JOBS_TOPIC, job);
    }
}
