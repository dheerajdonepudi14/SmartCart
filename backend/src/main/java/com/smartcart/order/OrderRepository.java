package com.smartcart.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"items", "user"})
    @Query("select o from CustomerOrder o where o.id = :id")
    Optional<Order> findDetailedById(@Param("id") Long id);

    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    @Query("""
            select count(oi) from OrderItem oi
            where oi.order.user.id = :userId and oi.product.id = :productId
              and oi.order.status in :statuses
            """)
    long countPurchases(@Param("userId") Long userId, @Param("productId") Long productId,
                        @Param("statuses") java.util.Collection<OrderStatus> statuses);

    default boolean hasPurchased(Long userId, Long productId, java.util.Collection<OrderStatus> statuses) {
        return countPurchases(userId, productId, statuses) > 0;
    }
}
