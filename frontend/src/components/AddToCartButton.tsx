"use client";

import { useState } from "react";
import { useCart } from "@/context/CartContext";
import { useLiveStock } from "@/context/LiveStockContext";
import type { Product } from "@/lib/types";

export function AddToCartButton({
  product,
  quantity = 1,
  compact = false,
}: {
  product: Product;
  quantity?: number;
  compact?: boolean;
}) {
  const { add } = useCart();
  const [busy, setBusy] = useState(false);
  const stock = useLiveStock(product.id, product.stock);
  const soldOut = stock <= 0;

  return (
    <button
      type="button"
      className={`btn btn--primary ${compact ? "btn--sm" : "btn--lg"}`}
      disabled={soldOut || busy}
      aria-label={soldOut ? `${product.name} is out of stock` : `Add ${product.name} to cart`}
      onClick={async () => {
        setBusy(true);
        try {
          await add({ ...product, stock }, quantity);
        } finally {
          setBusy(false);
        }
      }}
    >
      {soldOut ? "Out of stock" : busy ? "Adding…" : "Add to cart"}
    </button>
  );
}
