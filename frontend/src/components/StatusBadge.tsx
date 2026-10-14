import { STATUS_LABEL } from "@/lib/format";
import type { OrderStatus } from "@/lib/types";

export function StatusBadge({ status }: { status: OrderStatus }) {
  return <span className={`status status--${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>;
}

const STEPS: OrderStatus[] = ["PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED"];

/** Horizontal progress tracker for an order's lifecycle. */
export function OrderTracker({ status }: { status: OrderStatus }) {
  if (status === "CANCELLED") {
    return <p className="alert alert--error">This order was cancelled.</p>;
  }
  const current = STEPS.indexOf(status);
  return (
    <ol className="tracker" aria-label="Order progress">
      {STEPS.map((s, i) => (
        <li
          key={s}
          className={`tracker__step ${i < current ? "is-done" : ""} ${i === current ? "is-current" : ""}`}
          aria-current={i === current ? "step" : undefined}
        >
          <span className="tracker__dot" aria-hidden="true" />
          <span className="tracker__label">{STATUS_LABEL[s]}</span>
        </li>
      ))}
    </ol>
  );
}
