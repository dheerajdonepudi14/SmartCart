package com.smartcart.payment;

import com.smartcart.order.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByGatewayAndGatewayOrderId(PaymentGatewayType gateway, String gatewayOrderId);

    Optional<Payment> findFirstByOrderIdOrderByIdDesc(Long orderId);

    /** The newest still-open attempt at a gateway, reused on retry instead of creating a new gateway order. */
    Optional<Payment> findFirstByOrderIdAndGatewayAndStatusOrderByIdDesc(Long orderId, PaymentGatewayType gateway,
                                                                         PaymentStatus status);

    Optional<Payment> findFirstByOrderIdAndGatewayOrderByIdDesc(Long orderId, PaymentGatewayType gateway);

    long countByOrderIdAndGateway(Long orderId, PaymentGatewayType gateway);

    List<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status);

    List<Payment> findByStatusAndGateway(PaymentStatus status, PaymentGatewayType gateway);

    /** Serialises concurrent confirmations (browser callback vs. webhook vs. reconciler). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p join fetch p.order where p.id = :id")
    Optional<Payment> lockById(@Param("id") Long id);

    @Query("""
            select count(p) from Payment p
            where p.order.id = :orderId and p.id <> :excludeId and p.status in :statuses
            """)
    long countOtherWithStatus(@Param("orderId") Long orderId, @Param("excludeId") Long excludeId,
                              @Param("statuses") Collection<PaymentStatus> statuses);

    @Query("""
            select p from Payment p join fetch p.order o
            where p.status = :paymentStatus
              and o.status = :orderStatus
              and p.createdAt < :olderThan
            """)
    List<Payment> findPendingForReconciliation(@Param("paymentStatus") PaymentStatus paymentStatus,
                                               @Param("orderStatus") OrderStatus orderStatus,
                                               @Param("olderThan") Instant olderThan);
}
