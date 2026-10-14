"use client";

import { useEffect, useState } from "react";
import { api, queryString } from "@/lib/api";
import type { Product } from "@/lib/types";
import { ProductCard } from "./ProductCard";

export function Recommendations({ productId, title = "You may also like" }: { productId?: number; title?: string }) {
  const [items, setItems] = useState<Product[] | null>(null);

  useEffect(() => {
    let active = true;
    api<Product[]>(`/recommendations${queryString({ productId, limit: 6 })}`, { auth: false })
      .then((r) => active && setItems(r))
      .catch(() => active && setItems([]));
    return () => {
      active = false;
    };
  }, [productId]);

  if (!items || items.length === 0) return null;
  return (
    <section className="recs" aria-labelledby="recs-heading">
      <h2 id="recs-heading">{title}</h2>
      <div className="recs__row" role="list">
        {items.map((p) => (
          <div role="listitem" key={p.id} className="recs__item">
            <ProductCard product={p} />
          </div>
        ))}
      </div>
    </section>
  );
}
