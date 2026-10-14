"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { subscribeSSE } from "@/lib/sse";
import type { StockLevel } from "@/lib/types";

interface LiveStock {
  levels: Record<number, number>;
  connected: boolean;
}

const LiveStockContext = createContext<LiveStock>({ levels: {}, connected: false });

/**
 * One shared connection to the backend's live inventory feed. Whenever any shopper reserves stock
 * (or an order is cancelled / an admin restocks), every open storefront updates instantly —
 * the quick-commerce "only 2 left" behaviour.
 */
export function LiveStockProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<LiveStock>({ levels: {}, connected: false });

  useEffect(() => {
    const stream = subscribeSSE(
      "/products/stock/stream",
      (event, data) => {
        if (event !== "stock" || !Array.isArray(data)) return;
        setState((s) => {
          const levels = { ...s.levels };
          for (const item of data as StockLevel[]) levels[item.productId] = item.stock;
          return { ...s, levels };
        });
      },
      (connected) => setState((s) => ({ ...s, connected })),
    );
    return () => stream.close();
  }, []);

  return <LiveStockContext.Provider value={state}>{children}</LiveStockContext.Provider>;
}

/** Latest known stock for a product: the live value if one has arrived, otherwise the fetched one. */
export function useLiveStock(productId: number, fetched: number): number {
  const { levels } = useContext(LiveStockContext);
  return levels[productId] ?? fetched;
}

export function useLiveStockConnected(): boolean {
  return useContext(LiveStockContext).connected;
}
