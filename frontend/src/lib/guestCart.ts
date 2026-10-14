import type { Cart, CartLine, Product } from "./types";
import { round2, shippingFor } from "./format";

const KEY = "smartcart.guestCart.v1";
export const MAX_QTY = 10;

/** Browser-side cart for shoppers who are not signed in; merged into the account cart on login. */
export function readGuestCart(): CartLine[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(KEY);
    const parsed = raw ? (JSON.parse(raw) as CartLine[]) : [];
    return Array.isArray(parsed) ? parsed.filter((l) => l && typeof l.productId === "number") : [];
  } catch {
    return [];
  }
}

export function writeGuestCart(lines: CartLine[]) {
  try {
    if (lines.length) window.localStorage.setItem(KEY, JSON.stringify(lines));
    else window.localStorage.removeItem(KEY);
  } catch {
    /* storage unavailable (private mode) – cart stays in memory for this page */
  }
}

export function upsertLine(lines: CartLine[], product: Product, quantity: number, additive: boolean): CartLine[] {
  const existing = lines.find((l) => l.productId === product.id);
  const target = Math.min(
    MAX_QTY,
    Math.max(0, additive ? quantity + (existing?.quantity ?? 0) : quantity),
    product.stock,
  );
  const others = lines.filter((l) => l.productId !== product.id);
  if (target <= 0) return others;
  const line: CartLine = {
    productId: product.id,
    name: product.name,
    imageUrl: product.imageUrl,
    price: product.price,
    quantity: target,
    stock: product.stock,
    available: product.stock >= target,
    lineTotal: round2(product.price * target),
  };
  return existing ? lines.map((l) => (l.productId === product.id ? line : l)) : [...lines, line];
}

export function toCart(lines: CartLine[]): Cart {
  const subtotal = round2(lines.reduce((s, l) => s + l.price * l.quantity, 0));
  const shippingFee = shippingFor(subtotal);
  return {
    items: lines,
    itemCount: lines.reduce((s, l) => s + l.quantity, 0),
    subtotal,
    shippingFee,
    total: round2(subtotal + shippingFee),
  };
}
