package com.smartcart.job;

import com.smartcart.common.ApiException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Admin-only (see SecurityConfig): job history, retries and generated invoices. */
@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Background jobs (admin)")
public class BackgroundJobController {
    private final BackgroundJobService service;
    private final BackgroundJobProcessor processor;

    public BackgroundJobController(BackgroundJobService service, BackgroundJobProcessor processor) {
        this.service = service;
        this.processor = processor;
    }

    /** The 100 most recent jobs, newest first. Live updates arrive on STOMP /topic/jobs. */
    @GetMapping
    public List<BackgroundJob> recent() {
        return service.recent();
    }

    @GetMapping("/order/{orderId}")
    public List<BackgroundJob> forOrder(@PathVariable Long orderId) {
        return service.forOrder(orderId);
    }

    @PostMapping("/{jobId}/retry")
    public BackgroundJob retry(@PathVariable Long jobId) {
        return service.retry(jobId);
    }

    @GetMapping(value = "/invoices/{orderId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> invoice(@PathVariable Long orderId) throws IOException {
        Path file = processor.invoiceFile(orderId);
        if (!Files.isRegularFile(file)) {
            throw ApiException.notFound("Invoice");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(Files.readString(file));
    }
}
