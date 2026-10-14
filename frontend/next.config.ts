import type { NextConfig } from "next";

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api";
const apiOrigin = (() => {
  try {
    return new URL(apiUrl).origin;
  } catch {
    return "";
  }
})();
// WebSocket (STOMP job monitor) origin: same host as the API, ws:// or wss://.
const wsOrigin = apiOrigin.replace(/^http/, "ws");
// Server-side target for the /api/auth/* rewrite below (e.g. https://smartcart-api.onrender.com).
const backendUrl = (process.env.BACKEND_URL ?? "").replace(/\/+$/, "");
const isDev = process.env.NODE_ENV !== "production";

// Content Security Policy: allows our API, Razorpay Checkout (script + iframe) and https images.
// PhonePe uses a full-page redirect, so it needs no CSP allowance here.
const csp = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ""} https://checkout.razorpay.com`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https:",
  "font-src 'self' data:",
  `connect-src 'self' ${apiOrigin} ${wsOrigin} https://*.razorpay.com${isDev ? " ws: http://localhost:*" : ""}`,
  "frame-src https://api.razorpay.com https://checkout.razorpay.com",
  "form-action 'self' https://*.razorpay.com",
  "base-uri 'self'",
  "object-src 'none'",
  "frame-ancestors 'none'",
].join("; ");

const nextConfig: NextConfig = {
  // Docker builds set NEXT_OUTPUT=standalone for a minimal runtime image.
  output: process.env.NEXT_OUTPUT === "standalone" ? "standalone" : undefined,
  poweredByHeader: false,
  reactStrictMode: true,
  compress: true,
  // Auth calls are proxied through this site so the refresh cookie is first-party (see lib/api.ts).
  async rewrites() {
    return backendUrl ? [{ source: "/api/auth/:path*", destination: `${backendUrl}/api/auth/:path*` }] : [];
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Content-Security-Policy", value: csp },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=(self \"https://checkout.razorpay.com\")" },
        ],
      },
    ];
  },
};

export default nextConfig;
