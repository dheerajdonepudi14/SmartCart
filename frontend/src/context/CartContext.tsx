"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, errorMessage } from "@/lib/api";
import { readGuestCart, toCart, upsertLine, writeGuestCart } from "@/lib/guestCart";
import type { Cart, CartLine, Product } from "@/lib/types";
import { useAuth } from "./AuthContext";
import { useAnnounce } from "./AnnouncerContext";

interface CartState {
  cart: Cart;
  loading: boolean;
  add(product: Product, quantity?: number): Promise<void>;
  setQuantity(product: Pick<Product, "id" | "name" | "price" | "stock" | "imageUrl">, quantity: number): Promise<void>;
  refresh(): Promise<void>;
}

const EMPTY: Cart = { items: [], itemCount: 0, subtotal: 0, shippingFee: 0, total: 0 };
const CartContext = createContext<CartState | null>(null);

export function CartProvider({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth();
  const announce = useAnnounce();
  const [cart, setCart] = useState<Cart>(EMPTY);
  const [loading, setLoading] = useState(true);
  const mergedFor = useRef<number | null>(null);

  const refresh = useCallback(async () => {
    if (!user) {
      setCart(toCart(readGuestCart()));
      return;
    }
    setCart(await api<Cart>("/cart"));
  }, [user]);

  // Load the right cart whenever auth state settles; merge the guest cart once after sign-in.
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        if (user) {
          const guest = readGuestCart();
          if (guest.length && mergedFor.current !== user.id) {
            mergedFor.current = user.id;
            const merged = await api<Cart>("/cart/merge", {
              method: "POST",
              body: { items: guest.map((l) => ({ productId: l.productId, quantity: l.quantity })) },
            });
            writeGuestCart([]);
            if (!cancelled) setCart(merged);
          } else {
            const c = await api<Cart>("/cart");
            if (!cancelled) setCart(c);
          }
        } else {
          mergedFor.current = null;
          setCart(toCart(readGuestCart()));
        }
      } catch {
        if (!cancelled) setCart(user ? EMPTY : toCart(readGuestCart()));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [user, ready]);

  const setQuantity = useCallback<CartState["setQuantity"]>(
    async (product, quantity) => {
      try {
        if (user) {
          setCart(await api<Cart>("/cart", { method: "POST", body: { productId: product.id, quantity } }));
        } else {
          const lines: CartLine[] = upsertLine(readGuestCart(), product as Product, quantity, false);
          writeGuestCart(lines);
          setCart(toCart(lines));
        }
        if (quantity === 0) announce(`${product.name} removed from cart`);
      } catch (e) {
        announce(errorMessage(e), "error");
        throw e;
      }
    },
    [user, announce],
  );

  const add = useCallback<CartState["add"]>(
    async (product, quantity = 1) => {
      try {
        if (user) {
          const existing = cart.items.find((l) => l.productId === product.id)?.quantity ?? 0;
          setCart(
            await api<Cart>("/cart", {
              method: "POST",
              body: { productId: product.id, quantity: Math.min(10, existing + quantity) },
            }),
          );
        } else {
          const lines = upsertLine(readGuestCart(), product, quantity, true);
          writeGuestCart(lines);
          setCart(toCart(lines));
        }
        announce(`${product.name} added to cart`, "success");
      } catch (e) {
        announce(errorMessage(e), "error");
      }
    },
    [user, cart.items, announce],
  );

  const value = useMemo(() => ({ cart, loading, add, setQuantity, refresh }), [cart, loading, add, setQuantity, refresh]);
  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartState {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error("useCart must be used inside CartProvider");
  return ctx;
}
