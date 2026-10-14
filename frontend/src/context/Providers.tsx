"use client";

import type { ReactNode } from "react";
import { AnnouncerProvider } from "./AnnouncerContext";
import { AuthProvider } from "./AuthContext";
import { CartProvider } from "./CartContext";
import { LiveStockProvider } from "./LiveStockContext";

export function Providers({ children }: { children: ReactNode }) {
  return (
    <AnnouncerProvider>
      <AuthProvider>
        <LiveStockProvider>
          <CartProvider>{children}</CartProvider>
        </LiveStockProvider>
      </AuthProvider>
    </AnnouncerProvider>
  );
}
