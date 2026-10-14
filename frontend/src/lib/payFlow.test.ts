import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CheckoutResponse } from "./types";

const api = vi.fn();
const openRazorpay = vi.fn();
vi.mock("./api", () => ({ api: (...a: unknown[]) => api(...a) }));
vi.mock("./razorpay", () => ({ openRazorpay: (...a: unknown[]) => openRazorpay(...a) }));

import { launchPayment } from "./payFlow";

const base: CheckoutResponse = {
  orderId: 42,
  orderStatus: "PENDING_PAYMENT",
  gateway: "RAZORPAY",
  amountPaise: 44800,
  currency: "INR",
  payUrl: "http://localhost:3000/pay/42",
  replayed: false,
  razorpay: {
    keyId: "rzp_test_abc",
    razorpayOrderId: "order_ABC",
    amount: 44800,
    currency: "INR",
    name: "Smart Cart",
    description: "Order #42",
    prefill: {},
  },
};

describe("launchPayment", () => {
  beforeEach(() => {
    api.mockReset();
    openRazorpay.mockReset();
  });

  it("verifies a successful Razorpay payment with the server", async () => {
    openRazorpay.mockResolvedValue({
      kind: "success",
      response: { razorpay_order_id: "order_ABC", razorpay_payment_id: "pay_1", razorpay_signature: "sig" },
    });
    api.mockResolvedValue({ orderId: 42, orderStatus: "PAID", paymentStatus: "SUCCEEDED" });
    await expect(launchPayment(base, vi.fn())).resolves.toEqual({ kind: "paid", orderId: 42 });
    expect(api).toHaveBeenCalledWith("/payments/razorpay/verify", {
      method: "POST",
      body: { orderId: 42, razorpayOrderId: "order_ABC", razorpayPaymentId: "pay_1", razorpaySignature: "sig" },
    });
  });

  it("never treats a closed modal as paid", async () => {
    openRazorpay.mockResolvedValue({ kind: "dismissed" });
    const r = await launchPayment(base, vi.fn());
    expect(r.kind).toBe("pending");
    expect(api).not.toHaveBeenCalled();
  });

  it("does not reopen checkout for an idempotent replay of a paid order", async () => {
    const navigate = vi.fn();
    const r = await launchPayment({ ...base, orderStatus: "PAID", replayed: true, razorpay: undefined }, navigate);
    expect(r.kind).toBe("navigated");
    expect(navigate).toHaveBeenCalledWith("/orders/42");
    expect(openRazorpay).not.toHaveBeenCalled();
  });

  it("reports pending when the server has not confirmed the capture yet", async () => {
    openRazorpay.mockResolvedValue({
      kind: "success",
      response: { razorpay_order_id: "order_ABC", razorpay_payment_id: "pay_1", razorpay_signature: "sig" },
    });
    api.mockResolvedValue({ orderId: 42, orderStatus: "PENDING_PAYMENT", paymentStatus: "CREATED", message: "Waiting" });
    await expect(launchPayment(base, vi.fn())).resolves.toMatchObject({ kind: "pending", message: "Waiting" });
  });
});
