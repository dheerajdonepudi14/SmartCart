"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { RequireAuth } from "@/components/RequireAuth";
import { StatusBadge } from "@/components/StatusBadge";
import { ProductImage } from "@/components/ProductImage";
import { Pagination } from "@/components/Pagination";
import { api, errorMessage } from "@/lib/api";
import { formatDate, formatINR } from "@/lib/format";
import type { OrderSummary, Page } from "@/lib/types";

function OrdersInner() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<OrderSummary> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    api<Page<OrderSummary>>(`/orders?page=${page}&size=10`)
      .then((d) => active && setData(d))
      .catch((e) => active && setError(errorMessage(e)));
    return () => {
      active = false;
    };
  }, [page]);

  return (
    <div className="container page">
      <h1>Your orders</h1>
      {error && <p className="alert alert--error">{error}</p>}
      {!data && !error && <p className="muted" role="status">Loading orders…</p>}
      {data && data.content.length === 0 && (
        <div className="empty card">
          <h2>No orders yet</h2>
          <Link href="/" className="btn btn--primary">
            Start shopping
          </Link>
        </div>
      )}
      {data && data.content.length > 0 && (
        <ul className="order-list">
          {data.content.map((o) => (
            <li key={o.id} className="card">
              <Link href={`/orders/${o.id}`} className="order-row">
                <span className="order-row__img">
                  <ProductImage src={o.firstItemImage} alt="" />
                </span>
                <span>
                  <span className="order-row__meta">
                    <strong>Order #{o.id}</strong>
                    <StatusBadge status={o.status} />
                  </span>
                  <span className="order-row__meta small muted">
                    <span>
                      {o.firstItemName}
                      {o.itemCount > 1 ? ` + ${o.itemCount - 1} more` : ""}
                    </span>
                    <span>
                      {formatINR(o.totalAmount)} · {formatDate(o.createdAt)}
                    </span>
                  </span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
    </div>
  );
}

export default function OrdersPage() {
  return (
    <RequireAuth>
      <OrdersInner />
    </RequireAuth>
  );
}
