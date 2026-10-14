"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api, errorMessage, queryString } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { Page, Review } from "@/lib/types";
import { useAuth } from "@/context/AuthContext";
import { useAnnounce } from "@/context/AnnouncerContext";
import { Rating } from "./Rating";

export function ProductReviews({ productId }: { productId: number }) {
  const [page, setPage] = useState<Page<Review> | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setPage(await api<Page<Review>>(`/reviews${queryString({ productId, size: 10 })}`, { auth: false }));
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [productId]);

  useEffect(() => {
    let active = true;
    api<Page<Review>>(`/reviews${queryString({ productId, size: 10 })}`, { auth: false })
      .then((p) => active && setPage(p))
      .catch((e) => active && setError(errorMessage(e)));
    return () => {
      active = false;
    };
  }, [productId]);

  return (
    <section className="reviews" aria-labelledby="reviews-heading">
      <h2 id="reviews-heading">Customer reviews</h2>
      <ReviewForm productId={productId} onCreated={load} />
      {error && <p className="alert alert--error">{error}</p>}
      {page && page.content.length === 0 && <p className="muted">No reviews yet. Be the first to review this product.</p>}
      <ul className="review-list">
        {page?.content.map((r) => (
          <li key={r.id} className="review">
            <div className="review__head">
              <strong>{r.userName}</strong>
              <Rating value={r.rating} />
              {r.verifiedPurchase && <span className="badge badge--ok">Verified purchase</span>}
            </div>
            {r.title && <p className="review__title">{r.title}</p>}
            {r.comment && <p>{r.comment}</p>}
            <p className="small muted">{formatDate(r.createdAt)}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}

function ReviewForm({ productId, onCreated }: { productId: number; onCreated: () => void }) {
  const { user } = useAuth();
  const announce = useAnnounce();
  const [rating, setRating] = useState(5);
  const [title, setTitle] = useState("");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  if (!user) {
    return (
      <p className="muted">
        <Link href={`/login?next=/products/${productId}`}>Sign in</Link> to write a review.
      </p>
    );
  }
  if (done) return <p className="alert alert--ok">Thanks! Your review has been posted.</p>;

  return (
    <form
      className="review-form card"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setError(null);
        try {
          await api<Review>("/reviews", { method: "POST", body: { productId, rating, title, comment } });
          setDone(true);
          announce("Review posted", "success");
          onCreated();
        } catch (err) {
          setError(errorMessage(err));
        } finally {
          setBusy(false);
        }
      }}
    >
      <fieldset className="stars-input">
        <legend>Your rating</legend>
        {[1, 2, 3, 4, 5].map((n) => (
          <label key={n} className={n <= rating ? "is-on" : undefined}>
            <input type="radio" name="rating" value={n} checked={rating === n} onChange={() => setRating(n)} />
            <span aria-hidden="true">★</span>
            <span className="sr-only">
              {n} star{n > 1 ? "s" : ""}
            </span>
          </label>
        ))}
      </fieldset>
      <label className="field">
        <span>Headline</span>
        <input value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} />
      </label>
      <label className="field">
        <span>Review</span>
        <textarea value={comment} maxLength={4000} rows={3} onChange={(e) => setComment(e.target.value)} />
      </label>
      {error && (
        <p className="alert alert--error" role="alert">
          {error}
        </p>
      )}
      <button className="btn btn--primary" disabled={busy}>
        {busy ? "Posting…" : "Submit review"}
      </button>
    </form>
  );
}
