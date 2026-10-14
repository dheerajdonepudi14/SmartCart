"use client";

import Link from "next/link";
import type { Product } from "@/lib/types";
import { discountPercent, formatINR } from "@/lib/format";
import { ProductImage } from "./ProductImage";
import { Rating } from "./Rating";
import { AddToCartButton } from "./AddToCartButton";
import { useLiveStock } from "@/context/LiveStockContext";

export function ProductCard({ product }: { product: Product }) {
  const off = discountPercent(product.price, product.mrp);
  const stock = useLiveStock(product.id, product.stock);
  return (
    <article className="card product-card">
      <Link href={`/products/${product.id}`} className="product-card__link">
        <div className="product-card__media">
          <ProductImage src={product.imageUrl} alt={product.name} className="product-card__img" />
          {off > 0 && <span className="badge badge--deal">{off}% off</span>}
        </div>
        <h3 className="product-card__title">{product.name}</h3>
      </Link>
      <Rating value={product.ratingAvg} count={product.ratingCount} />
      <p className="price">
        <span className="price__now">{formatINR(product.price)}</span>
        {off > 0 && (
          <span className="price__mrp">
            <span className="sr-only">M.R.P.</span>
            <s>{formatINR(product.mrp)}</s>
          </span>
        )}
      </p>
      {stock > 0 && stock <= 5 && (
        <p className="stock stock--low small" aria-live="polite">
          Only {stock} left
        </p>
      )}
      <AddToCartButton product={product} compact />
    </article>
  );
}
