import { formatINR, FREE_SHIPPING_ABOVE } from "@/lib/format";

export function OrderSummary({
  itemCount,
  subtotal,
  shippingFee,
  total,
  children,
}: {
  itemCount: number;
  subtotal: number;
  shippingFee: number;
  total: number;
  children?: React.ReactNode;
}) {
  const remaining = FREE_SHIPPING_ABOVE - subtotal;
  return (
    <aside className="summary card" aria-labelledby="summary-heading">
      <h2 id="summary-heading">Order summary</h2>
      <dl className="summary__rows">
        <div>
          <dt>
            Items ({itemCount})
          </dt>
          <dd>{formatINR(subtotal)}</dd>
        </div>
        <div>
          <dt>Delivery</dt>
          <dd>{shippingFee === 0 ? <span className="ok">FREE</span> : formatINR(shippingFee)}</dd>
        </div>
        <div className="summary__total">
          <dt>Order total</dt>
          <dd>{formatINR(total)}</dd>
        </div>
      </dl>
      {shippingFee > 0 && remaining > 0 && (
        <p className="small muted">Add {formatINR(remaining)} more for free delivery.</p>
      )}
      {children}
    </aside>
  );
}
