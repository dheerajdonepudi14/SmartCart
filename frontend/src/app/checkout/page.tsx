"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { RequireAuth } from "@/components/RequireAuth";
import { OrderSummary } from "@/components/OrderSummary";
import { useCart } from "@/context/CartContext";
import { useAuth } from "@/context/AuthContext";
import { useAnnounce } from "@/context/AnnouncerContext";
import { api, ApiError, errorMessage } from "@/lib/api";
import { launchPayment } from "@/lib/payFlow";
import type { Address, CheckoutResponse, Gateway, PaymentConfig } from "@/lib/types";
import { validateAddress } from "@/lib/validation";

const STATES = [
  "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat", "Haryana",
  "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya",
  "Mizoram", "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana", "Tripura",
  "Uttar Pradesh", "Uttarakhand", "West Bengal", "Andaman and Nicobar Islands", "Chandigarh",
  "Dadra and Nagar Haveli and Daman and Diu", "Delhi", "Jammu and Kashmir", "Ladakh", "Lakshadweep", "Puducherry",
];

const ADDRESS_KEY = "smartcart.lastAddress.v1";
const EMPTY: Address = { fullName: "", phone: "", line1: "", line2: "", city: "", state: "", postalCode: "" };


const GATEWAY_INFO: Record<Gateway, { title: string; blurb: string }> = {
  RAZORPAY: { title: "Razorpay", blurb: "UPI, credit & debit cards, net banking, wallets and EMI — pay without leaving this page." },
  PHONEPE: { title: "PhonePe", blurb: "Pay with the PhonePe app, any UPI app, cards or net banking on PhonePe's secure page." },
};

function modeLabel(mode: string) {
  switch (mode) {
    case "live":
    case "production":
      return null;
    case "test":
    case "sandbox":
      return <span className="badge badge--muted">Test mode</span>;
    default:
      return <span className="badge badge--muted">Not configured</span>;
  }
}

function CheckoutInner() {
  const { cart, loading } = useCart();
  const { user } = useAuth();
  const announce = useAnnounce();
  const router = useRouter();
  const [address, setAddress] = useState<Address>(() => {
    if (typeof window === "undefined") return EMPTY;
    try {
      return { ...EMPTY, ...JSON.parse(window.localStorage.getItem(ADDRESS_KEY) ?? "{}") };
    } catch {
      return EMPTY;
    }
  });
  const [touched, setTouched] = useState(false);
  const [config, setConfig] = useState<PaymentConfig | null>(null);
  const [gateway, setGateway] = useState<Gateway>("RAZORPAY");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  // One Idempotency-Key per checkout attempt: a double-click or a network retry re-sends the same
  // key, so the server returns the same order and Razorpay order instead of creating duplicates.
  const idempotencyKey = useRef<string | null>(null);

  useEffect(() => {
    api<PaymentConfig>("/payments/config", { auth: false })
      .then((c) => {
        setConfig(c);
        if (!c.gateways.RAZORPAY.available && c.gateways.PHONEPE.available) setGateway("PHONEPE");
      })
      .catch(() => setConfig(null));
  }, []);

  const errors = validateAddress(address);
  const gatewayReady = !config || config.gateways[gateway].available;
  const fieldError = (k: keyof Address) => (touched ? errors[k] : undefined) ?? serverErrors[`address.${k}`];
  const set = (k: keyof Address) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setAddress((a) => ({ ...a, [k]: e.target.value }));

  if (!loading && cart.items.length === 0 && !busy) {
    return (
      <div className="container page empty">
        <h1>Nothing to check out</h1>
        <Link href="/" className="btn btn--primary">
          Continue shopping
        </Link>
      </div>
    );
  }

  const placeOrder = async () => {
    setTouched(true);
    setError(null);
    setServerErrors({});
    if (Object.keys(errors).length) {
      announce("Please fix the highlighted fields", "error");
      document.querySelector<HTMLElement>("[aria-invalid='true']")?.focus();
      return;
    }
    setBusy(true);
    try {
      try {
        window.localStorage.setItem(ADDRESS_KEY, JSON.stringify(address));
      } catch {
        /* ignore */
      }
      idempotencyKey.current ??= crypto.randomUUID();
      const res = await api<CheckoutResponse>("/checkout", {
        method: "POST",
        body: { gateway, address },
        headers: { "Idempotency-Key": idempotencyKey.current },
      });
      const outcome = await launchPayment(res, (href) => router.push(href));
      if (outcome.kind === "paid") {
        announce("Payment successful! Order placed.", "success");
        router.push(`/orders/${outcome.orderId}?placed=1`);
      } else if (outcome.kind === "pending") {
        announce(outcome.message, "error");
        router.push(`/orders/${outcome.orderId}`);
      }
    } catch (e) {
      if (e instanceof ApiError) {
        setServerErrors(e.fieldErrors);
        // The server answered definitively; the next click is a new attempt. Network errors
        // (status 0) keep the key so the retry is recognised as the same attempt.
        if (e.status !== 0) idempotencyKey.current = null;
      }
      setError(errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <div className="container page">
      <h1>Checkout</h1>
      <div className="two-col">
        <div>
          <section className="card" aria-labelledby="addr-heading">
            <h2 id="addr-heading">1. Delivery address</h2>
            <p className="small muted">Delivering to {user?.name}</p>
            <form
              noValidate
              className="form-grid"
              onSubmit={(e) => {
                e.preventDefault();
                placeOrder();
              }}
            >
              <Field id="fullName" label="Full name" value={address.fullName} onChange={set("fullName")} error={fieldError("fullName")} autoComplete="name" className="span-2" />
              <Field id="phone" label="Mobile number" value={address.phone} onChange={set("phone")} error={fieldError("phone")} autoComplete="tel-national" inputMode="numeric" maxLength={10} />
              <Field id="postalCode" label="PIN code" value={address.postalCode} onChange={set("postalCode")} error={fieldError("postalCode")} autoComplete="postal-code" inputMode="numeric" maxLength={6} />
              <Field id="line1" label="Flat, house no., building, street" value={address.line1} onChange={set("line1")} error={fieldError("line1")} autoComplete="address-line1" className="span-2" />
              <Field id="line2" label="Area, landmark (optional)" value={address.line2 ?? ""} onChange={set("line2")} autoComplete="address-line2" className="span-2" required={false} />
              <Field id="city" label="Town / city" value={address.city} onChange={set("city")} error={fieldError("city")} autoComplete="address-level2" />
              <label className="field" htmlFor="state">
                <span>State</span>
                <select
                  id="state"
                  value={address.state}
                  onChange={set("state")}
                  aria-required="true"
                  aria-invalid={!!fieldError("state")}
                  aria-describedby={fieldError("state") ? "state-error" : undefined}
                  autoComplete="address-level1"
                >
                  <option value="">Select state</option>
                  {STATES.map((s) => (
                    <option key={s}>{s}</option>
                  ))}
                </select>
                {fieldError("state") && (
                  <span id="state-error" className="field__error">
                    {fieldError("state")}
                  </span>
                )}
              </label>
            </form>
          </section>

          <section className="card" style={{ marginTop: 16 }} aria-labelledby="pay-heading">
            <h2 id="pay-heading">2. Payment method</h2>
            <fieldset className="pay-options">
              <legend className="sr-only">Choose a payment gateway</legend>
              {(["RAZORPAY", "PHONEPE"] as Gateway[]).map((g) => {
                const info = config?.gateways[g];
                const disabled = !!config && !info?.available;
                return (
                  <label key={g} className="pay-option" aria-disabled={disabled}>
                    <input
                      type="radio"
                      name="gateway"
                      value={g}
                      checked={gateway === g}
                      disabled={disabled}
                      onChange={() => setGateway(g)}
                    />
                    <span>
                      <span className="pay-option__title">
                        {GATEWAY_INFO[g].title} {info && modeLabel(info.mode)}
                      </span>
                      <span className="small muted">{GATEWAY_INFO[g].blurb}</span>
                    </span>
                  </label>
                );
              })}
            </fieldset>
          </section>
        </div>

        <OrderSummary itemCount={cart.itemCount} subtotal={cart.subtotal} shippingFee={cart.shippingFee} total={cart.total}>
          {error && (
            <p className="alert alert--error" role="alert">
              {error}
            </p>
          )}
          {!gatewayReady && (
            <p className="alert alert--error" role="alert">
              Online payments are not configured on the server yet. Add your Razorpay API keys to the backend
              environment (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET) and restart it.
            </p>
          )}
          <button
            type="button"
            className="btn btn--primary btn--block btn--lg"
            disabled={busy || loading || !gatewayReady}
            onClick={placeOrder}
          >
            {busy ? "Processing…" : `Pay with ${GATEWAY_INFO[gateway].title}`}
          </button>
          <p className="small muted" style={{ marginTop: 8 }}>
            Your order is confirmed the moment the payment gateway verifies your payment. Stock is held for 30 minutes.
          </p>
        </OrderSummary>
      </div>
    </div>
  );
}

function Field({
  id,
  label,
  error,
  className,
  required = true,
  ...input
}: {
  id: string;
  label: string;
  error?: string;
  className?: string;
  required?: boolean;
} & React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className={`field ${className ?? ""}`} htmlFor={id}>
      <span>{label}</span>
      <input
        id={id}
        aria-required={required}
        aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : undefined}
        {...input}
      />
      {error && (
        <span id={`${id}-error`} className="field__error">
          {error}
        </span>
      )}
    </label>
  );
}

export default function CheckoutPage() {
  return (
    <RequireAuth>
      <CheckoutInner />
    </RequireAuth>
  );
}
