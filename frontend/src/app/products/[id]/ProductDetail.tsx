"use client";

import Link from "next/link";
import { useState } from "react";
import type { Product } from "@/lib/types";
import { discountPercent, formatINR, FREE_SHIPPING_ABOVE } from "@/lib/format";
import { ImageCarousel } from "@/components/ImageCarousel";
import { Rating } from "@/components/Rating";
import { AddToCartButton } from "@/components/AddToCartButton";
import { ProductReviews } from "@/components/ProductReviews";
import { Recommendations } from "@/components/Recommendations";
import { useLiveStock, useLiveStockConnected } from "@/context/LiveStockContext";

export function ProductDetail({ product }: { product: Product }) {
  const [qty, setQty] = useState(1);
  const off = discountPercent(product.price, product.mrp);
  const stock = useLiveStock(product.id, product.stock);
  const live = useLiveStockConnected();
  const maxQty = Math.max(1, Math.min(10, stock));
  const quantity = Math.min(qty, maxQty);
  const images = product.images.length ? product.images : product.imageUrl ? [product.imageUrl] : [];
  const specs = Object.entries(product.attributes ?? {});

  return (
    <div className="container page">
      <nav aria-label="Breadcrumb" className="small muted" style={{ marginBottom: 12 }}>
        <Link href="/">Home</Link>
        {product.categoryName && (
          <>
            {" › "}
            <Link href={`/?categoryId=${product.categoryId}`}>{product.categoryName}</Link>
          </>
        )}
      </nav>

      <div className="pdp">
        <ImageCarousel images={images} alt={product.name} />

        <div className="pdp__info">
          <h1>{product.name}</h1>
          <Rating value={product.ratingAvg} count={product.ratingCount} />
          <p className="price" style={{ marginTop: 12 }}>
            {off > 0 && <span className="badge badge--deal" style={{ position: "static" }}>-{off}%</span>}
            <span className="price__now">{formatINR(product.price)}</span>
            {off > 0 && (
              <span className="price__mrp">
                M.R.P.: <s>{formatINR(product.mrp)}</s>
              </span>
            )}
          </p>
          <p className="small muted">Inclusive of all taxes</p>
          {product.description && <p>{product.description}</p>}
          {specs.length > 0 && (
            <>
              <h2 className="small" style={{ marginTop: 16 }}>
                Specifications
              </h2>
              <dl className="pdp__specs">
                {specs.map(([k, v]) => (
                  <div key={k} style={{ display: "contents" }}>
                    <dt>{k}</dt>
                    <dd>{v}</dd>
                  </div>
                ))}
              </dl>
            </>
          )}
        </div>

        <aside className="card pdp__buy" aria-label="Purchase options">
          <p className="price__now">{formatINR(product.price)}</p>
          <p className="small muted">
            {product.price >= FREE_SHIPPING_ABOVE ? "FREE delivery" : `Free delivery on orders above ${formatINR(FREE_SHIPPING_ABOVE)}`}
          </p>
          <p
            className={`stock ${stock === 0 ? "stock--out" : stock <= 5 ? "stock--low" : "ok"}`}
            role="status"
          >
            {stock === 0 ? "Currently unavailable" : stock <= 5 ? `Only ${stock} left in stock` : "In stock"}
            {live && (
              <span className="live" style={{ marginLeft: 8 }} title="Stock updates live">
                <span className="live__dot is-on" aria-hidden="true" /> live
              </span>
            )}
          </p>
          {stock > 0 && (
            <div>
              <span id="qty-label" className="small" style={{ fontWeight: 700, display: "block", marginBottom: 4 }}>
                Quantity
              </span>
              <div className="qty" role="group" aria-labelledby="qty-label">
                <button type="button" aria-label="Decrease quantity" disabled={quantity <= 1} onClick={() => setQty(quantity - 1)}>
                  −
                </button>
                <output aria-live="polite">{quantity}</output>
                <button type="button" aria-label="Increase quantity" disabled={quantity >= maxQty} onClick={() => setQty(quantity + 1)}>
                  +
                </button>
              </div>
            </div>
          )}
          <AddToCartButton product={product} quantity={quantity} />
          <p className="small muted">Secure transaction · UPI, cards, net banking & wallets</p>
        </aside>
      </div>

      <ProductReviews productId={product.id} />
      <Recommendations productId={product.id} />
    </div>
  );
}
