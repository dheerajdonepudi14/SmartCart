"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCart } from "@/context/CartContext";
import { useAuth } from "@/context/AuthContext";
import { formatINR } from "@/lib/format";
import { ProductImage } from "@/components/ProductImage";
import { OrderSummary } from "@/components/OrderSummary";
import { Recommendations } from "@/components/Recommendations";

export default function CartPage() {
  const { cart, loading, setQuantity } = useCart();
  const { user } = useAuth();
  const router = useRouter();
  const blocked = cart.items.some((l) => !l.available);

  const change = (line: (typeof cart.items)[number], quantity: number) =>
    setQuantity({ id: line.productId, name: line.name, price: line.price, stock: line.stock, imageUrl: line.imageUrl }, quantity).catch(
      () => undefined,
    );

  return (
    <div className="container page">
      <h1>Shopping cart</h1>
      {loading && <p className="muted" role="status">Loading your cart…</p>}
      {!loading && cart.items.length === 0 && (
        <div className="empty card">
          <h2>Your cart is empty</h2>
          <p className="muted">Browse the catalogue and add something you love.</p>
          <Link href="/" className="btn btn--primary">
            Continue shopping
          </Link>
        </div>
      )}
      {cart.items.length > 0 && (
        <div className="two-col">
          <section className="card" aria-label="Cart items">
            <ul className="cart-lines">
              {cart.items.map((line) => (
                <li key={line.productId} className="cart-line">
                  <Link href={`/products/${line.productId}`} className="cart-line__img" tabIndex={-1} aria-hidden="true">
                    <ProductImage src={line.imageUrl} alt="" />
                  </Link>
                  <div className="cart-line__meta">
                    <Link href={`/products/${line.productId}`} style={{ fontWeight: 700, color: "inherit" }}>
                      {line.name}
                    </Link>
                    <span className="price__now">{formatINR(line.price)}</span>
                    {!line.available && (
                      <span className="stock stock--out">
                        {line.stock === 0 ? "Out of stock — remove to continue" : `Only ${line.stock} available`}
                      </span>
                    )}
                    <div className="cart-line__actions">
                      <label className="small">
                        <span className="sr-only">Quantity for {line.name}</span>
                        <select
                          value={line.quantity}
                          onChange={(e) => change(line, Number(e.target.value))}
                          style={{ padding: "6px 10px", borderRadius: 8, minHeight: 36 }}
                        >
                          {Array.from({ length: Math.max(line.quantity, Math.min(10, Math.max(1, line.stock))) }, (_, i) => i + 1).map((n) => (
                            <option key={n} value={n}>
                              Qty: {n}
                            </option>
                          ))}
                        </select>
                      </label>
                      <button type="button" className="link-btn" onClick={() => change(line, 0)}>
                        Remove<span className="sr-only"> {line.name}</span>
                      </button>
                      <span className="muted small">Subtotal {formatINR(line.lineTotal)}</span>
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          </section>
          <OrderSummary
            itemCount={cart.itemCount}
            subtotal={cart.subtotal}
            shippingFee={cart.shippingFee}
            total={cart.total}
          >
            <button
              type="button"
              className="btn btn--primary btn--block"
              disabled={blocked}
              onClick={() => router.push(user ? "/checkout" : "/login?next=/checkout")}
            >
              {user ? "Proceed to checkout" : "Sign in to checkout"}
            </button>
          </OrderSummary>
        </div>
      )}
      <Recommendations title="Customers also viewed" />
    </div>
  );
}
