package com.smartcart.order;

import com.smartcart.payment.Payment;
import com.smartcart.payment.PaymentGatewayType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {
    private OrderDtos() {}

    public record AddressDto(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a 10-digit Indian mobile number") String phone,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 100) String state,
            @NotBlank @Pattern(regexp = "^[1-9]\\d{5}$", message = "must be a 6-digit PIN code") String postalCode) {

        public ShippingAddress toEntity() {
            return new ShippingAddress(fullName.trim(), phone, line1.trim(), line2 == null ? null : line2.trim(),
                    city.trim(), state.trim(), postalCode);
        }

        public static AddressDto of(ShippingAddress a) {
            return new AddressDto(a.getFullName(), a.getPhone(), a.getLine1(), a.getLine2(), a.getCity(),
                    a.getState(), a.getPostalCode());
        }
    }

    public record CheckoutRequest(@NotNull PaymentGatewayType gateway, @NotNull @jakarta.validation.Valid AddressDto address) {}

    public record OrderItemDto(Long productId, String name, String imageUrl, BigDecimal unitPrice, int quantity,
                               BigDecimal lineTotal) {
        public static OrderItemDto of(OrderItem i) {
            return new OrderItemDto(i.getProduct().getId(), i.getProductName(), i.getImageUrl(), i.getUnitPrice(),
                    i.getQuantity(), i.lineTotal());
        }
    }

    public record PaymentDto(Long id, PaymentGatewayType gateway, String status, String gatewayOrderId,
                             String gatewayPaymentId, String method, long amountPaise, String refundId,
                             String failureReason, Instant createdAt) {
        public static PaymentDto of(Payment p) {
            return p == null ? null : new PaymentDto(p.getId(), p.getGateway(), p.getStatus().name(),
                    p.getGatewayOrderId(), p.getGatewayPaymentId(), p.getMethod(), p.getAmountPaise(),
                    p.getRefundId(), p.getFailureReason(), p.getCreatedAt());
        }
    }

    public record OrderSummaryDto(Long id, OrderStatus status, BigDecimal totalAmount, String currency,
                                  int itemCount, String firstItemName, String firstItemImage,
                                  PaymentGatewayType paymentGateway, Instant createdAt) {}

    public record OrderDetailDto(Long id, OrderStatus status, BigDecimal subtotal, BigDecimal shippingFee,
                                 BigDecimal totalAmount, String currency, PaymentGatewayType paymentGateway,
                                 AddressDto address, List<OrderItemDto> items, PaymentDto latestPayment,
                                 String customerEmail, Instant createdAt, Instant updatedAt) {}

    public record StatusUpdateRequest(@NotNull OrderStatus status) {}
}
