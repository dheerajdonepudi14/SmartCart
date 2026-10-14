import { api } from "./api";
import { openRazorpay } from "./razorpay";
import type { CheckoutResponse, PaymentResult } from "./types";

export type LaunchResult =
  | { kind: "navigated" }
  | { kind: "paid"; orderId: number }
  | { kind: "pending"; orderId: number; message: string };

/**
 * Hands a payment to the real gateway:
 *  - Razorpay: in-page Checkout modal; the server then verifies the signature, fetches the payment
 *    from Razorpay, captures it and marks the order paid.
 *  - PhonePe: full-page redirect to PhonePe's hosted page; it returns to /orders/{id}?pg=phonepe.
 * There is no simulated path: an order is paid only when the gateway confirms real money.
 */
export async function launchPayment(res: CheckoutResponse, navigate: (href: string) => void): Promise<LaunchResult> {
  if (res.orderStatus !== "PENDING_PAYMENT") {
    // Idempotent replay of a checkout that is already paid.
    navigate(`/orders/${res.orderId}`);
    return { kind: "navigated" };
  }
  if (res.redirectUrl) {
    window.location.assign(res.redirectUrl);
    return { kind: "navigated" };
  }
  if (!res.razorpay) throw new Error("The server did not return payment details");

  const outcome = await openRazorpay(res.razorpay);
  if (outcome.kind === "success") {
    const r = outcome.response;
    const result = await api<PaymentResult>("/payments/razorpay/verify", {
      method: "POST",
      body: {
        orderId: res.orderId,
        razorpayOrderId: r.razorpay_order_id,
        razorpayPaymentId: r.razorpay_payment_id,
        razorpaySignature: r.razorpay_signature,
      },
    });
    if (result.orderStatus === "PAID" || result.paymentStatus === "SUCCEEDED") {
      return { kind: "paid", orderId: res.orderId };
    }
    return { kind: "pending", orderId: res.orderId, message: result.message ?? "Waiting for Razorpay to confirm the payment." };
  }
  return {
    kind: "pending",
    orderId: res.orderId,
    message:
      outcome.kind === "failed"
        ? `Payment failed: ${outcome.reason}. You can retry — the same order is reused, so you will not be charged twice.`
        : "Payment was not completed. You can retry from this page.",
  };
}
