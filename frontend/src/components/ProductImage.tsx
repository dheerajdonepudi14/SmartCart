"use client";

import { useState } from "react";

/** Product photo with lazy loading and a branded placeholder if the CDN image fails. */
export function ProductImage({
  src,
  alt,
  className,
  eager = false,
}: {
  src?: string | null;
  alt: string;
  className?: string;
  eager?: boolean;
}) {
  const [failed, setFailed] = useState(false);
  if (!src || failed) {
    return (
      <div className={`img-fallback ${className ?? ""}`} role="img" aria-label={alt}>
        <span aria-hidden="true">{alt.trim().charAt(0).toUpperCase() || "S"}</span>
      </div>
    );
  }
  return (
    <img
      src={src}
      alt={alt}
      className={className}
      loading={eager ? "eager" : "lazy"}
      decoding="async"
      onError={() => setFailed(true)}
    />
  );
}
