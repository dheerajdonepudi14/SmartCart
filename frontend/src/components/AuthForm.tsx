"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import { ApiError, errorMessage } from "@/lib/api";

/** Only allow same-site relative redirects after sign-in (prevents open redirects). */
export function safeNext(next: string | null): string {
  return next && next.startsWith("/") && !next.startsWith("//") ? next : "/";
}

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const { login, register } = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const next = safeNext(params.get("next"));
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const isRegister = mode === "register";

  return (
    <div className="container">
      <section className="card auth-card" aria-labelledby="auth-heading">
        <h1 id="auth-heading">{isRegister ? "Create your account" : "Sign in"}</h1>
        <form
          noValidate={false}
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setError(null);
            setFieldErrors({});
            try {
              if (isRegister) await register(name, email, password);
              else await login(email, password);
              router.replace(next);
            } catch (err) {
              if (err instanceof ApiError) setFieldErrors(err.fieldErrors);
              setError(errorMessage(err));
            } finally {
              setBusy(false);
            }
          }}
        >
          {isRegister && (
            <label className="field" htmlFor="name">
              <span>Your name</span>
              <input id="name" required aria-required="true" autoComplete="name" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
              {fieldErrors.name && <span className="field__error">{fieldErrors.name}</span>}
            </label>
          )}
          <label className="field" htmlFor="email">
            <span>Email</span>
            <input id="email" type="email" required aria-required="true" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
            {fieldErrors.email && <span className="field__error">{fieldErrors.email}</span>}
          </label>
          <label className="field" htmlFor="password">
            <span>Password</span>
            <input
              id="password"
              type="password"
              required
              aria-required="true"
              minLength={isRegister ? 8 : undefined}
              maxLength={72}
              autoComplete={isRegister ? "new-password" : "current-password"}
              aria-describedby={isRegister ? "pw-hint" : undefined}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            {isRegister && (
              <span id="pw-hint" className="small muted">
                At least 8 characters, including a letter and a number.
              </span>
            )}
            {fieldErrors.password && <span className="field__error">{fieldErrors.password}</span>}
          </label>
          {error && (
            <p className="alert alert--error" role="alert">
              {error}
            </p>
          )}
          <button className="btn btn--primary btn--block" disabled={busy}>
            {busy ? "Please wait…" : isRegister ? "Create account" : "Sign in"}
          </button>
        </form>
        <p className="small" style={{ marginTop: 16 }}>
          {isRegister ? (
            <>
              Already have an account? <Link href={`/login?next=${encodeURIComponent(next)}`}>Sign in</Link>
            </>
          ) : (
            <>
              New to Smart Cart? <Link href={`/register?next=${encodeURIComponent(next)}`}>Create an account</Link>
            </>
          )}
        </p>
      </section>
    </div>
  );
}

export function AuthPage({ mode }: { mode: "login" | "register" }) {
  return (
    <Suspense fallback={<p className="container page muted">Loading…</p>}>
      <AuthForm mode={mode} />
    </Suspense>
  );
}
