import type { RazorpayOptions } from "./types";

interface RazorpaySuccess {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
}

interface RazorpayFailure {
  error: { code?: string; description?: string; reason?: string; metadata?: { payment_id?: string } };
}

interface RazorpayInstance {
  open(): void;
  on(event: "payment.failed", cb: (resp: RazorpayFailure) => void): void;
}

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => RazorpayInstance;
  }
}

const SCRIPT_SRC = "https://checkout.razorpay.com/v1/checkout.js";
let loading: Promise<void> | null = null;

export function loadRazorpay(): Promise<void> {
  if (typeof window === "undefined") return Promise.reject(new Error("Razorpay needs a browser"));
  if (window.Razorpay) return Promise.resolve();
  if (!loading) {
    loading = new Promise<void>((resolve, reject) => {
      const s = document.createElement("script");
      s.src = SCRIPT_SRC;
      s.async = true;
      s.onload = () => resolve();
      s.onerror = () => {
        loading = null;
        reject(new Error("Could not load Razorpay Checkout. Check your connection or ad blocker."));
      };
      document.body.appendChild(s);
    });
  }
  return loading;
}

export type RazorpayOutcome =
  | { kind: "success"; response: RazorpaySuccess }
  | { kind: "failed"; reason: string }
  | { kind: "dismissed" };

/** Opens Razorpay Checkout and resolves once the shopper pays, fails, or closes the modal. */
export async function openRazorpay(opts: RazorpayOptions): Promise<RazorpayOutcome> {
  await loadRazorpay();
  const Razorpay = window.Razorpay;
  if (!Razorpay) throw new Error("Razorpay Checkout failed to initialise");

  return new Promise<RazorpayOutcome>((resolve) => {
    let lastFailure: string | null = null;
    const rzp = new Razorpay({
      key: opts.keyId,
      amount: opts.amount,
      currency: opts.currency,
      name: opts.name,
      description: opts.description,
      order_id: opts.razorpayOrderId,
      prefill: opts.prefill,
      theme: { color: "#ea580c" },
      handler: (response: RazorpaySuccess) => resolve({ kind: "success", response }),
      modal: {
        // Razorpay lets the shopper retry inside the modal after a failure; we only give up on close.
        ondismiss: () => resolve(lastFailure ? { kind: "failed", reason: lastFailure } : { kind: "dismissed" }),
      },
    });
    rzp.on("payment.failed", (resp) => {
      lastFailure = resp.error?.description ?? "Payment failed";
    });
    rzp.open();
  });
}
