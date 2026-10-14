"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { RequireAuth } from "@/components/RequireAuth";
import { OrderTracker, StatusBadge } from "@/components/StatusBadge";
import { ProductImage } from "@/components/ProductImage";
import { useAnnounce } from "@/context/AnnouncerContext";
import { useCart } from "@/context/CartContext";
import { api, errorMessage } from "@/lib/api";
import { formatDate, formatINR, STATUS_LABEL } from "@/lib/format";
import { launchPayment } from "@/lib/payFlow";
import { subscribeSSE } from "@/lib/sse";
import type { CheckoutResponse, Gateway, OrderDetail, OrderStatusEvent, PaymentConfig, PaymentResult } from "@/lib/types";

function OrderInner() {
  const { id } = useParams<{ id: string }>();
  const search = useSearchParams();
  const router = useRouter();
  const announce = useAnnounce();
  const { refresh: refreshCart } = useCart();
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [live, setLive] = useState(false);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const lastStatus = useRef<string | null>(null);
  const [config, setConfig] = useState<PaymentConfig | null>(null);

  useEffect(() => {
    api<PaymentConfig>("/payments/config", { auth: false }).then(setConfig).catch(() => setConfig(null));
  }, []);

  const load = useCallback(async () => {
    try {
      const o = await api<OrderDetail>(`/orders/${id}`);
      setOrder(o);
      return o;
    } catch (e) {
      setError(errorMessage(e));
      return null;
    }
  }, [id]);

  // Initial load; when returning from PhonePe, ask the server to confirm with PhonePe right away.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (search.get("pg") === "phonepe") {
        try {
          const r = await api<PaymentResult>(`/payments/phonepe/sync/${id}`, { method: "POST" });
          if (!cancelled && r.paymentStatus === "FAILED") setNote("PhonePe reported the payment as failed. You can try again.");
        } catch {
          /* webhook + reconciler will still settle it */
        }
      }
      if (!cancelled) await load();
    })();
    return () => {
      cancelled = true;
    };
  }, [id, search, load]);

  // Real-time status over SSE, with a slow poll as a safety net.
  useEffect(() => {
    const stream = subscribeSSE(
      `/orders/${id}/events`,
      (event, data) => {
        if (event !== "status") return;
        const ev = data as Partial<OrderStatusEvent> & Partial<OrderDetail>;
        if (ev.status && lastStatus.current && ev.status !== lastStatus.current) {
          announce(ev.message ?? `Order is now ${STATUS_LABEL[ev.status]}`, ev.status === "CANCELLED" ? "error" : "success");
        } else if (ev.paymentStatus === "FAILED" && ev.message) {
          announce(ev.message, "error");
        }
        load();
      },
      setLive,
    );
    const poll = setInterval(load, 15_000);
    return () => {
      stream.close();
      clearInterval(poll);
    };
  }, [id, load, announce]);

  useEffect(() => {
    if (order?.status === "PAID" && lastStatus.current === "PENDING_PAYMENT") refreshCart().catch(() => undefined);
    lastStatus.current = order?.status ?? null;
  }, [order?.status, refreshCart]);

  const retry = async (gateway: Gateway) => {
    setBusy(true);
    setNote(null);
    try {
      const res = await api<CheckoutResponse>(`/orders/${id}/pay`, { method: "POST", body: { gateway } });
      const outcome = await launchPayment(res, (href) => router.push(href));
      if (outcome.kind === "paid") {
        announce("Payment successful!", "success");
        await load();
      } else if (outcome.kind === "pending") {
        setNote(outcome.message);
      }
    } catch (e) {
      setNote(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    if (!window.confirm("Cancel this unpaid order?")) return;
    setBusy(true);
    try {
      setOrder(await api<OrderDetail>(`/orders/${id}/cancel`, { method: "POST" }));
      announce("Order cancelled");
    } catch (e) {
      setNote(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  if (error) {
    return (
      <div className="container page">
        <h1>Order not found</h1>
        <p className="muted">{error}</p>
        <Link href="/orders">Back to your orders</Link>
      </div>
    );
  }
  if (!order) return <p className="container page muted" role="status">Loading order…</p>;

  const pay = order.latestPayment;
  const pending = order.status === "PENDING_PAYMENT";

  return (
    <div className="container page">
      {search.get("placed") === "1" && order.status === "PAID" && (
        <p className="alert alert--ok" role="status">
          Thank you! Your payment was received and your order is confirmed.
        </p>
      )}
      <div className="catalog__head">
        <h1>Order #{order.id}</h1>
        <span className="live" title="Live updates">
          <span className={`live__dot ${live ? "is-on" : ""}`} aria-hidden="true" />
          {live ? "Live" : "Reconnecting…"}
        </span>
      </div>
      <p className="muted small">
        Placed {formatDate(order.createdAt)} · <StatusBadge status={order.status} />
      </p>
      <OrderTracker status={order.status} />

      {pending && (
        <div className="alert alert--info" role="status">
          <p style={{ margin: "0 0 8px" }}>
            {pay?.status === "FAILED"
              ? `Your last payment attempt failed${pay.failureReason ? ` (${pay.failureReason})` : ""}.`
              : pay?.status === "CREATED"
                ? "Waiting for confirmation from the payment gateway — this page updates automatically."
                : "This order is awaiting payment."}{" "}
            Stock is held for 30 minutes from when the order was placed.
          </p>
          {note && <p style={{ margin: "0 0 8px" }}>{note}</p>}
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            {config?.gateways.RAZORPAY.available !== false && (
              <button className="btn btn--primary btn--sm" disabled={busy} onClick={() => retry("RAZORPAY")}>
                Pay with Razorpay
              </button>
            )}
            {config?.gateways.PHONEPE.available && (
              <button className="btn btn--primary btn--sm" disabled={busy} onClick={() => retry("PHONEPE")}>
                Pay with PhonePe
              </button>
            )}
            <button className="btn btn--danger btn--sm" disabled={busy} onClick={cancel}>
              Cancel order
            </button>
          </div>
        </div>
      )}
      {!pending && note && <p className="alert alert--info">{note}</p>}

      <div className="two-col" style={{ marginTop: 16 }}>
        <section className="card" aria-labelledby="items-heading">
          <h2 id="items-heading">Items</h2>
          <ul className="cart-lines">
            {order.items.map((it) => (
              <li key={it.productId} className="cart-line">
                <span className="cart-line__img">
                  <ProductImage src={it.imageUrl} alt="" />
                </span>
                <span className="cart-line__meta">
                  <Link href={`/products/${it.productId}`} style={{ fontWeight: 700, color: "inherit" }}>
                    {it.name}
                  </Link>
                  <span className="small muted">
                    {it.quantity} × {formatINR(it.unitPrice)}
                  </span>
                  <strong>{formatINR(it.lineTotal)}</strong>
                </span>
              </li>
            ))}
          </ul>
        </section>
        <aside className="card" aria-labelledby="details-heading" style={{ alignSelf: "start" }}>
          <h2 id="details-heading">Details</h2>
          <dl className="kv">
            <dt>Subtotal</dt>
            <dd>{formatINR(order.subtotal)}</dd>
            <dt>Delivery</dt>
            <dd>{order.shippingFee === 0 ? "FREE" : formatINR(order.shippingFee)}</dd>
            <dt>Total</dt>
            <dd>
              <strong>{formatINR(order.totalAmount)}</strong>
            </dd>
            <dt>Payment</dt>
            <dd>
              {pay ? `${pay.gateway === "RAZORPAY" ? "Razorpay" : "PhonePe"} · ${pay.status.toLowerCase().replace("_", " ")}` : "—"}
              {pay?.method && ` · ${pay.method.toUpperCase()}`}
            </dd>
            {pay?.gatewayPaymentId && (
              <>
                <dt>Reference</dt>
                <dd>{pay.gatewayPaymentId}</dd>
              </>
            )}
            {pay?.refundId && (
              <>
                <dt>Refund</dt>
                <dd>{pay.refundId}</dd>
              </>
            )}
            <dt>Ship to</dt>
            <dd>
              {order.address.fullName}
              <br />
              {order.address.line1}
              {order.address.line2 ? `, ${order.address.line2}` : ""}
              <br />
              {order.address.city}, {order.address.state} {order.address.postalCode}
              <br />
              {order.address.phone}
            </dd>
          </dl>
        </aside>
      </div>
    </div>
  );
}

export default function OrderPage() {
  return (
    <RequireAuth>
      <Suspense fallback={<p className="container page muted">Loading order…</p>}>
        <OrderInner />
      </Suspense>
    </RequireAuth>
  );
}
