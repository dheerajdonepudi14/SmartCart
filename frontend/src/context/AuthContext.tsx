"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, refreshSession, registerSessionListener, setAccessToken } from "@/lib/api";
import type { AuthResponse, User } from "@/lib/types";

interface AuthState {
  user: User | null;
  ready: boolean;
  isAdmin: boolean;
  login(email: string, password: string): Promise<User>;
  register(name: string, email: string, password: string): Promise<User>;
  logout(): Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    registerSessionListener((session) => setUser(session?.user ?? null));
    // Restore the session from the HttpOnly refresh cookie on first load.
    refreshSession().finally(() => setReady(true));
    return () => registerSessionListener(null);
  }, []);

  const accept = useCallback((res: AuthResponse) => {
    setAccessToken(res.accessToken);
    setUser(res.user);
    return res.user;
  }, []);

  const login = useCallback(
    async (email: string, password: string) =>
      accept(await api<AuthResponse>("/auth/login", { method: "POST", body: { email, password }, auth: false })),
    [accept],
  );

  const register = useCallback(
    async (name: string, email: string, password: string) =>
      accept(
        await api<AuthResponse>("/auth/register", { method: "POST", body: { name, email, password }, auth: false }),
      ),
    [accept],
  );

  const logout = useCallback(async () => {
    try {
      await api<void>("/auth/logout", { method: "POST", auth: false });
    } finally {
      setAccessToken(null);
      setUser(null);
    }
  }, []);

  const value = useMemo<AuthState>(
    () => ({ user, ready, isAdmin: user?.role === "ROLE_ADMIN", login, register, logout }),
    [user, ready, login, register, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}
