"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { RequireAuth } from "@/components/RequireAuth";
import { useAnnounce } from "@/context/AnnouncerContext";
import { api, errorMessage } from "@/lib/api";
import { formatINR } from "@/lib/format";
import { launchPayment } from "@/lib/payFlow";
import type { CheckoutResponse, OrderDetail, PaymentConfig } from "@/lib/types";

/**
 * Hosted "pay this order" page. The checkout API returns its URL as `payUrl`, so an order created
 * from Postman (or any API client) can be paid with real Razorpay Checkout in the browser.
 * Opening it again never creates a second charge: the backend reuses the open Razorpay order.
 */
function PayInner() {
  const { orderId } = useParams<{ orderId: string }>();
  const router = useRouter();
  const announce = useAnnounce();
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [config, setConfig] = useState<PaymentConfig | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    Promise.all([api<OrderDetail>(`/orders/${orderId}`), api<PaymentConfig>("/payments/config", { auth: false })])
      .then(([o, c]) => {
        if (!active) return;
        setOrder(o);
        setConfig(c);
      })
      .catch((e) => active && setError(errorMessage(e)));
    return () => {
      active = false;
    };
  }, [orderId]);

  const pay = async () => {
    setBusy(true);
    setNote(null);
    try {
      const res = await api<CheckoutResponse>(`/orders/${orderId}/pay`, { method: "POST", body: { gateway: "RAZORPAY" } });
      const outcome = await launchPayment(res, (href) => router.push(href));
      if (outcome.kind === "paid") {
        announce("Payment successful!", "success");
        router.replace(`/orders/${orderId}?placed=1`);
      } else if (outcome.kind === "pending") {
        setNote(outcome.message);
      }
    } catch (e) {
      setNote(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  if (error) {
    return (
      <div className="container page">
        <h1>Can’t open this payment</h1>
        <p className="alert alert--error">{error}</p>
        <Link href="/orders">Your orders</Link>
      </div>
    );
  }
  if (!order || !config) return <p className="container page muted" role="status">Loading payment…</p>;

  const razorpay = config.gateways.RAZORPAY;
  return (
    <div className="container">
      <section className="card sim" aria-labelledby="pay-heading">
        <h1 id="pay-heading">Pay for order #{order.id}</h1>
        <p className="price__now" style={{ fontSize: "2rem" }}>
          {formatINR(order.totalAmount)}
        </p>
        {order.status !== "PENDING_PAYMENT" ? (
          <>
            <p className="alert alert--ok">This order is {order.status.toLowerCase().replace("_", " ")} — nothing to pay.</p>
            <Link href={`/orders/${order.id}`} className="btn btn--primary">
              View order
            </Link>
          </>
        ) : !razorpay.available ? (
          <p className="alert alert--error">Razorpay is not configured on the server.</p>
        ) : (
          <>
            {razorpay.mode === "test" && (
              <p className="small muted">
                Razorpay <strong>test mode</strong>: use UPI ID <code>success@razorpay</code> or a Razorpay test card. No real
                money moves.
              </p>
            )}
            {note && (
              <p className="alert alert--info" role="status">
                {note}
              </p>
            )}
            <button type="button" className="btn btn--primary btn--lg" disabled={busy} onClick={pay}>
              {busy ? "Opening Razorpay…" : "Pay with Razorpay"}
            </button>
            <p className="small muted" style={{ marginTop: 12 }}>
              UPI, cards, net banking and wallets. Your order updates live the moment Razorpay confirms.
            </p>
          </>
        )}
      </section>
    </div>
  );
}

export default function PayPage() {
  return (
    <RequireAuth>
      <PayInner />
    </RequireAuth>
  );
}
