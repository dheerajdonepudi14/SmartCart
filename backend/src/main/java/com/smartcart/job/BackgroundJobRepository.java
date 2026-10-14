package com.smartcart.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BackgroundJobRepository extends JpaRepository<BackgroundJob, Long> {

    List<BackgroundJob> findTop100ByOrderByJobIdDesc();

    List<BackgroundJob> findByOrderIdOrderByJobId(Long orderId);

    boolean existsByOrderId(Long orderId);
}
