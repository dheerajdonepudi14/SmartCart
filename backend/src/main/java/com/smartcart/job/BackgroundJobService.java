package com.smartcart.job;

import com.smartcart.common.ApiException;
import com.smartcart.config.AsyncConfig;
import com.smartcart.order.OrderStatus;
import com.smartcart.order.OrderStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Dispatcher: once a payment is verified (Razorpay checkout callback, webhook or reconciliation,
 * all of which end in OrderService#applyPaymentSuccess), records one PENDING job per task and
 * hands each to the async processor. The HTTP response never waits for them.
 */
@Service
public class BackgroundJobService {
    private static final Logger log = LoggerFactory.getLogger(BackgroundJobService.class);

    private final BackgroundJobRepository repository;
    private final BackgroundJobProcessor processor;
    private final JobNotificationService notifier;

    public BackgroundJobService(BackgroundJobRepository repository, BackgroundJobProcessor processor,
                                JobNotificationService notifier) {
        this.repository = repository;
        this.processor = processor;
        this.notifier = notifier;
    }

    /** Runs only after the PAID status is committed, so jobs never act on a rolled-back payment. */
    @Async(AsyncConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        if (event.status() == OrderStatus.PAID && "SUCCEEDED".equals(event.paymentStatus())) {
            startOrderJobs(event.orderId());
        }
    }

    public void startOrderJobs(Long orderId) {
        if (repository.existsByOrderId(orderId)) {
            log.info("Background jobs already queued for order {}", orderId);
            return;
        }
        for (JobType type : JobType.values()) {
            BackgroundJob saved = repository.save(new BackgroundJob(orderId, type));
            notifier.publish(saved);
            processor.process(saved.getJobId());
        }
        log.info("Queued background jobs for order {}", orderId);
    }

    /** Re-runs a FAILED job, e.g. after fixing the SMTP settings. */
    public BackgroundJob retry(Long jobId) {
        BackgroundJob job = repository.findById(jobId).orElseThrow(() -> ApiException.notFound("Job"));
        if (job.getStatus() != JobStatus.FAILED) {
            throw ApiException.conflict("Only failed jobs can be retried");
        }
        job.moveTo(JobStatus.PENDING, "Retry requested");
        BackgroundJob saved = repository.save(job);
        notifier.publish(saved);
        processor.process(saved.getJobId());
        return saved;
    }

    public List<BackgroundJob> recent() {
        return repository.findTop100ByOrderByJobIdDesc();
    }

    public List<BackgroundJob> forOrder(Long orderId) {
        return repository.findByOrderIdOrderByJobId(orderId);
    }
}
