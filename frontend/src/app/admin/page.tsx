"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { RequireAuth } from "@/components/RequireAuth";
import { AdminProductList } from "@/components/AdminProductList";
import { BackgroundJobMonitor } from "@/components/BackgroundJobMonitor";
import { StatusBadge } from "@/components/StatusBadge";
import { Pagination } from "@/components/Pagination";
import { useAnnounce } from "@/context/AnnouncerContext";
import { api, errorMessage, queryString } from "@/lib/api";
import { formatDate, formatINR, STATUS_LABEL } from "@/lib/format";
import { subscribeSSE } from "@/lib/sse";
import type { OrderStatus, OrderStatusEvent, OrderSummary, Page } from "@/lib/types";

const NEXT_ACTIONS: Partial<Record<OrderStatus, { status: OrderStatus; label: string }[]>> = {
  PAID: [
    { status: "SHIPPED", label: "Mark shipped" },
    { status: "CANCELLED", label: "Cancel & refund" },
  ],
  SHIPPED: [{ status: "DELIVERED", label: "Mark delivered" }],
  PENDING_PAYMENT: [{ status: "CANCELLED", label: "Cancel" }],
};

function AdminOrders() {
  const announce = useAnnounce();
  const [filter, setFilter] = useState<OrderStatus | "">("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<OrderSummary> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [live, setLive] = useState(false);
  const [feed, setFeed] = useState<OrderStatusEvent[]>([]);

  const load = useCallback(async () => {
    try {
      setData(await api<Page<OrderSummary>>(`/admin/orders${queryString({ status: filter, page, size: 20 })}`));
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [filter, page]);

  useEffect(() => {
    let active = true;
    api<Page<OrderSummary>>(`/admin/orders${queryString({ status: filter, page, size: 20 })}`)
      .then((d) => active && setData(d))
      .catch((e) => active && setError(errorMessage(e)));
    return () => {
      active = false;
    };
  }, [filter, page]);

  // Live feed: every payment confirmation / status change across the store appears instantly.
  useEffect(() => {
    const s = subscribeSSE(
      "/admin/orders/events",
      (event, payload) => {
        if (event !== "order") return;
        setFeed((f) => [payload as OrderStatusEvent, ...f].slice(0, 20));
        load();
      },
      setLive,
    );
    return () => s.close();
  }, [load]);

  const update = async (id: number, status: OrderStatus) => {
    if (status === "CANCELLED" && !window.confirm(`Cancel order #${id}?`)) return;
    try {
      await api(`/admin/orders/${id}/status`, { method: "PUT", body: { status } });
      announce(`Order #${id} → ${STATUS_LABEL[status]}`, "success");
      load();
    } catch (e) {
      announce(errorMessage(e), "error");
    }
  };

  return (
    <section aria-labelledby="ao-heading">
      <div className="catalog__head">
        <h2 id="ao-heading">Orders</h2>
        <span className="live">
          <span className={`live__dot ${live ? "is-on" : ""}`} aria-hidden="true" />
          {live ? "Live feed" : "Connecting…"}
        </span>
      </div>
      <div className="tabs" role="group" aria-label="Filter by status">
        {(["", "PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"] as const).map((s) => (
          <button
            key={s || "all"}
            type="button"
            className={`btn btn--sm ${filter === s ? "btn--primary" : "btn--ghost"}`}
            aria-pressed={filter === s}
            onClick={() => {
              setFilter(s);
              setPage(0);
            }}
          >
            {s ? STATUS_LABEL[s] : "All"}
          </button>
        ))}
      </div>
      {error && <p className="alert alert--error">{error}</p>}
      {feed.length > 0 && (
        <div className="alert alert--info" aria-live="polite">
          <strong>Latest:</strong> Order #{feed[0].orderId} → {STATUS_LABEL[feed[0].status]}
          {feed[0].message ? ` — ${feed[0].message}` : ""}
        </div>
      )}
      <div className="table-wrap card" style={{ padding: 0 }}>
        <table className="table">
          <thead>
            <tr>
              <th scope="col">Order</th>
              <th scope="col">Placed</th>
              <th scope="col">Items</th>
              <th scope="col">Total</th>
              <th scope="col">Gateway</th>
              <th scope="col">Status</th>
              <th scope="col">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map((o) => (
              <tr key={o.id}>
                <td>
                  <Link href={`/orders/${o.id}`}>#{o.id}</Link>
                </td>
                <td>{formatDate(o.createdAt)}</td>
                <td>{o.itemCount}</td>
                <td>{formatINR(o.totalAmount)}</td>
                <td>{o.paymentGateway === "RAZORPAY" ? "Razorpay" : "PhonePe"}</td>
                <td>
                  <StatusBadge status={o.status} />
                </td>
                <td style={{ whiteSpace: "nowrap" }}>
                  {(NEXT_ACTIONS[o.status] ?? []).map((a) => (
                    <button key={a.status} type="button" className="link-btn" onClick={() => update(o.id, a.status)}>
                      {a.label}
                      <span className="sr-only"> for order {o.id}</span>
                    </button>
                  ))}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {data && data.content.length === 0 && <p className="muted" style={{ padding: 16 }}>No orders.</p>}
      </div>
      {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
    </section>
  );
}

function AdminInner() {
  const [tab, setTab] = useState<"orders" | "products">("orders");
  return (
    <div className="container page">
      <h1>Admin dashboard</h1>
      <div className="tabs" role="tablist" aria-label="Admin sections">
        {(["orders", "products"] as const).map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            id={`tab-${t}`}
            aria-selected={tab === t}
            aria-controls={`panel-${t}`}
            className={`btn ${tab === t ? "btn--primary" : "btn--ghost"}`}
            onClick={() => setTab(t)}
          >
            {t === "orders" ? "Orders" : "Products"}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {tab === "orders" ? (
          <>
            <AdminOrders />
            <BackgroundJobMonitor />
          </>
        ) : (
          <AdminProductList />
        )}
      </div>
    </div>
  );
}

export default function AdminPage() {
  return (
    <RequireAuth admin>
      <AdminInner />
    </RequireAuth>
  );
}
