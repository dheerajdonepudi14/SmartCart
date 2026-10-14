import type { AuthResponse } from "./types";

export const API_URL = (process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api").replace(/\/+$/, "");

/**
 * Base for /auth/* calls. When the API is on another site (Vercel frontend + Render backend), set
 * NEXT_PUBLIC_AUTH_URL=/api so these go through the same-origin rewrite in next.config.ts: the
 * HttpOnly refresh cookie is then first-party and survives browsers that block third-party cookies.
 */
export const AUTH_URL = (process.env.NEXT_PUBLIC_AUTH_URL || API_URL).replace(/\/+$/, "");

function baseFor(path: string) {
  return path.startsWith("/auth/") ? AUTH_URL : API_URL;
}

/** Structured error carrying the server's RFC 7807 problem details. */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public fieldErrors: Record<string, string> = {},
  ) {
    super(message);
    this.name = "ApiError";
  }
}

// The access token lives only in memory (never localStorage); the refresh token is an HttpOnly cookie.
let accessToken: string | null = null;
let refreshInFlight: Promise<AuthResponse | null> | null = null;
let onSessionChange: ((session: AuthResponse | null) => void) | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

export function registerSessionListener(fn: ((session: AuthResponse | null) => void) | null) {
  onSessionChange = fn;
}

/** Exchanges the refresh cookie for a new access token. Concurrent callers share one request. */
export function refreshSession(): Promise<AuthResponse | null> {
  if (!refreshInFlight) {
    refreshInFlight = fetch(`${AUTH_URL}/auth/refresh`, { method: "POST", credentials: "include" })
      .then(async (res) => {
        if (!res.ok) {
          setAccessToken(null);
          return null;
        }
        const session = (await res.json()) as AuthResponse;
        setAccessToken(session.accessToken);
        return session;
      })
      .catch(() => null)
      .then((session) => {
        onSessionChange?.(session);
        return session;
      })
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

type Options = Omit<RequestInit, "body"> & { body?: unknown; auth?: boolean };

export async function api<T>(path: string, opts: Options = {}, retried = false): Promise<T> {
  const { body, auth = true, headers, ...rest } = opts;
  const h = new Headers(headers);
  h.set("Accept", "application/json");
  if (body !== undefined) h.set("Content-Type", "application/json");
  if (auth && accessToken) h.set("Authorization", `Bearer ${accessToken}`);

  let res: Response;
  try {
    res = await fetch(`${baseFor(path)}${path}`, {
      ...rest,
      headers: h,
      credentials: "include",
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "Cannot reach the server. Check your connection and try again.");
  }

  if (res.status === 401 && auth && !retried && !path.startsWith("/auth/")) {
    const session = await refreshSession();
    if (session) return api<T>(path, opts, true);
  }
  if (!res.ok) {
    let message = res.statusText || "Request failed";
    let fieldErrors: Record<string, string> = {};
    try {
      const problem = await res.json();
      message = problem.detail ?? problem.message ?? message;
      fieldErrors = problem.errors ?? {};
    } catch {
      /* non-JSON error body */
    }
    if (res.status === 429) message = "Too many requests. Please wait a moment and try again.";
    throw new ApiError(res.status, message, fieldErrors);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    const fields = Object.entries(e.fieldErrors);
    return fields.length ? `${e.message}: ${fields.map(([k, v]) => `${k} ${v}`).join(", ")}` : e.message;
  }
  return e instanceof Error ? e.message : "Something went wrong";
}

export function queryString(params: Record<string, string | number | boolean | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") q.set(k, String(v));
  }
  const s = q.toString();
  return s ? `?${s}` : "";
}
