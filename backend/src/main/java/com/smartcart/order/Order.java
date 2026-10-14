package com.smartcart.order;

import com.smartcart.payment.PaymentGatewayType;
import com.smartcart.user.User;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Entity name avoids the JPQL keyword ORDER; the table is "orders". */
@Entity(name = "CustomerOrder")
@Table(name = "orders", uniqueConstraints = @UniqueConstraint(name = "uq_orders_user_idempotency",
        columnNames = {"user_id", "idempotency_key"}))
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "shipping_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_gateway", nullable = false, length = 20)
    private PaymentGatewayType paymentGateway;

    @Embedded
    private ShippingAddress address;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    /** Client-supplied Idempotency-Key for the checkout request that created this order. */
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Order() {}

    public Order(User user, PaymentGatewayType gateway, ShippingAddress address, String idempotencyKey) {
        this.user = user;
        this.paymentGateway = gateway;
        this.address = address;
        this.idempotencyKey = idempotencyKey;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    public void setTotals(BigDecimal subtotal, BigDecimal shippingFee) {
        this.subtotal = subtotal;
        this.shippingFee = shippingFee;
        this.totalAmount = subtotal.add(shippingFee);
    }

    /** Amount in the smallest currency unit (paise), as both gateways expect. */
    public long totalPaise() {
        return totalAmount.movePointRight(2).longValueExact();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getShippingFee() { return shippingFee; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public PaymentGatewayType getPaymentGateway() { return paymentGateway; }
    public void setPaymentGateway(PaymentGatewayType paymentGateway) { this.paymentGateway = paymentGateway; }
    public ShippingAddress getAddress() { return address; }
    public List<OrderItem> getItems() { return items; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
