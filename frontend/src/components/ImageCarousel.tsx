"use client";

import { useState } from "react";
import { ProductImage } from "./ProductImage";

/** Accessible image gallery: thumbnails act as a tab list controlling the main image panel. */
export function ImageCarousel({ images, alt }: { images: string[]; alt: string }) {
  const list = images.length ? images : [""];
  const [index, setIndex] = useState(0);
  const current = list[Math.min(index, list.length - 1)];

  const move = (delta: number) => setIndex((i) => (i + delta + list.length) % list.length);

  return (
    <div className="carousel">
      <div className="carousel__main" role="tabpanel" id="carousel-panel" aria-label={`Image ${index + 1} of ${list.length}`}>
        <ProductImage key={current} src={current} alt={alt} className="carousel__img" eager />
        {list.length > 1 && (
          <>
            <button type="button" className="carousel__nav carousel__nav--prev" aria-label="Previous image" onClick={() => move(-1)}>
              ‹
            </button>
            <button type="button" className="carousel__nav carousel__nav--next" aria-label="Next image" onClick={() => move(1)}>
              ›
            </button>
          </>
        )}
      </div>
      {list.length > 1 && (
        <div className="carousel__thumbs" role="tablist" aria-label="Product images">
          {list.map((src, i) => (
            <button
              key={src + i}
              type="button"
              role="tab"
              aria-selected={i === index}
              aria-controls="carousel-panel"
              aria-label={`Show image ${i + 1}`}
              className={`carousel__thumb ${i === index ? "is-active" : ""}`}
              onClick={() => setIndex(i)}
            >
              <ProductImage src={src} alt="" />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
