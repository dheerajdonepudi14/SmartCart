# Deploying Smart Cart (Render + Vercel)

```
Browser ──► Vercel (Next.js frontend) ──/api/auth/* rewrite──┐
   │                                                          ▼
   └──── REST, SSE, WebSocket (wss) ───────────────► Render web service (Spring Boot, Docker)
                                                              │
                                                              ▼
                                                     Render PostgreSQL (free)
```

Login/refresh calls go through Vercel so the HttpOnly refresh cookie is first-party (Safari and
other browsers block third-party cookies). Everything else goes straight to Render.

Order matters: **GitHub → Render (backend + DB) → Vercel (frontend) → back to Render for the URLs.**

## 1. GitHub

The code lives at https://github.com/dheerajdonepudi14/SmartCart. `.env` is git-ignored, so keys and
passwords are never uploaded. Render and Vercel both deploy from this repository and redeploy on
every push to `main`.

## 2. Backend + database on Render

1. https://dashboard.render.com → **New → Blueprint** → connect GitHub → pick the repo.
   Render reads `render.yaml` and creates **smartcart-db** (PostgreSQL) and **smartcart-api** (Docker).
2. It asks for the `sync: false` values:

   | Key | Value |
   |---|---|
   | `FRONTEND_URL` | `https://placeholder.vercel.app` for now (fixed in step 4) |
   | `CORS_ALLOWED_ORIGINS` | same as `FRONTEND_URL` |
   | `ADMIN_EMAIL` / `ADMIN_PASSWORD` | the admin login to create (use a strong password) |
   | `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` | from your `.env` (test keys are fine) |
   | `RAZORPAY_WEBHOOK_SECRET` | from your `.env` |
   | `MAIL_USERNAME` / `MAIL_FROM` | your Gmail address |
   | `MAIL_PASSWORD` | your 16-letter Google App Password (no spaces) |

3. **Apply**. The first build takes ~5–8 minutes. When it is live, open
   `https://smartcart-api.onrender.com/actuator/health` → `{"status":"UP"}` (your exact URL is shown
   at the top of the service page; use it everywhere below).

## 3. Frontend on Vercel

1. https://vercel.com/new → import the same GitHub repo.
2. **Root Directory: `frontend`** (Framework preset: Next.js is detected).
3. Environment Variables (replace the Render URL with yours):

   | Key | Value |
   |---|---|
   | `NEXT_PUBLIC_API_URL` | `https://smartcart-api.onrender.com/api` |
   | `NEXT_PUBLIC_AUTH_URL` | `/api` |
   | `BACKEND_URL` | `https://smartcart-api.onrender.com` |
   | `API_INTERNAL_URL` | `https://smartcart-api.onrender.com/api` |

4. **Deploy**. Note the production URL, e.g. `https://smart-cart-xyz.vercel.app`.

## 4. Connect them

1. Render → smartcart-api → **Environment**: set `FRONTEND_URL` and `CORS_ALLOWED_ORIGINS` to the
   Vercel URL (no trailing slash) → **Save, rebuild and deploy**.
2. Razorpay Dashboard → **Webhooks** → URL `https://smartcart-api.onrender.com/api/webhooks/razorpay`,
   secret = `RAZORPAY_WEBHOOK_SECRET`, events `payment.authorized`, `payment.captured`,
   `payment.failed`, `order.paid`.

## 5. Check it works

- Vercel URL loads the catalogue (products come from the seeded database).
- Register → welcome email arrives. Reload the page → you are still signed in (cookie works).
- Pay with UPI `success@razorpay` → order PAID, confirmation email, and on `/admin` the Background
  Job Monitor shows EMAIL / INVOICE / NOTIFICATION completing live (status "Live (WebSocket)").

## Free-tier limits to know

- **Render free web services sleep after 15 minutes idle**; the first request then takes ~1 minute.
  Open the API health URL a minute before a demo.
- **Render free PostgreSQL expires 30 days after creation** (export or upgrade before then).
- Generated invoice files are on the container's disk and are lost on each redeploy/restart.
- If EMAIL jobs fail on Render with a connection timeout, outbound SMTP is being blocked on the free
  plan; upgrading the web service to Starter, or switching to an HTTP email API, fixes it.
- Vercel *preview* deployments get different URLs that are not in `CORS_ALLOWED_ORIGINS`; use the
  production URL.

## Netlify instead of Vercel

Same idea: base directory `frontend`, the same four environment variables; Netlify's Next.js runtime
supports the `/api/auth/*` rewrite in `next.config.ts`.
