"use client";

import { useEffect, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";

/** Client-side guard: sends anonymous users to sign in (the API enforces access regardless). */
export function RequireAuth({ children, admin = false }: { children: ReactNode; admin?: boolean }) {
  const { user, ready, isAdmin } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (ready && !user) router.replace(`/login?next=${encodeURIComponent(pathname)}`);
  }, [ready, user, router, pathname]);

  if (!ready || !user) {
    return (
      <p className="container page muted" role="status">
        Loading…
      </p>
    );
  }
  if (admin && !isAdmin) {
    return (
      <div className="container page">
        <h1>Not authorised</h1>
        <p className="muted">This area is for store administrators.</p>
      </div>
    );
  }
  return <>{children}</>;
}
