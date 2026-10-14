"use client";

export default function GlobalError({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <div className="container page empty">
      <h1>Something went wrong</h1>
      <p className="muted">An unexpected error occurred. Please try again.</p>
      <button type="button" className="btn btn--primary" onClick={() => reset()}>
        Try again
      </button>
    </div>
  );
}
