package com.smartcart.job;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "background_jobs")
public class BackgroundJob {
    static final int MESSAGE_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 20)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(length = MESSAGE_MAX)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected BackgroundJob() {}

    public BackgroundJob(Long orderId, JobType jobType) {
        this.orderId = orderId;
        this.jobType = jobType;
        this.status = JobStatus.PENDING;
        this.message = "Waiting for processing";
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void moveTo(JobStatus status, String message) {
        this.status = status;
        this.message = message == null || message.length() <= MESSAGE_MAX ? message : message.substring(0, MESSAGE_MAX);
    }

    public Long getJobId() { return jobId; }
    public Long getOrderId() { return orderId; }
    public JobType getJobType() { return jobType; }
    public JobStatus getStatus() { return status; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
