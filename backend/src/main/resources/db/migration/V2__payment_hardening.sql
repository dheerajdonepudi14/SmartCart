-- Checkout idempotency: the same Idempotency-Key from the same user always maps to one order,
-- so a double-click, a network retry or a Postman re-send can never create a second order.
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(64);
CREATE UNIQUE INDEX uq_orders_user_idempotency ON orders (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- A gateway payment id (Razorpay pay_..., PhonePe transaction id) can settle at most one row.
CREATE UNIQUE INDEX uq_payments_gateway_payment ON payments (gateway, gateway_payment_id)
    WHERE gateway_payment_id IS NOT NULL;

-- Payments now also record refunds (duplicate charges, cancelled paid orders).
ALTER TABLE payments ADD COLUMN refund_id VARCHAR(100);
ALTER TABLE payments ADD COLUMN method VARCHAR(30);

-- The local payment simulator was removed; drop its marker column.
ALTER TABLE payments DROP COLUMN simulated;

CREATE INDEX idx_payments_status ON payments (status);
