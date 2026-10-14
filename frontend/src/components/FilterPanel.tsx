"use client";

import { useState } from "react";
import type { Category } from "@/lib/types";

export interface Filters {
  categoryId?: string;
  minPrice?: string;
  maxPrice?: string;
  inStock?: boolean;
  sort?: string;
}

/** Remount with a `key` derived from the URL filters to reset the local price inputs. */
export function FilterPanel({
  categories,
  value,
  onChange,
}: {
  categories: Category[];
  value: Filters;
  onChange: (f: Filters) => void;
}) {
  const [min, setMin] = useState(value.minPrice ?? "");
  const [max, setMax] = useState(value.maxPrice ?? "");

  return (
    <aside className="filters card" aria-labelledby="filters-heading">
      <h2 id="filters-heading" className="filters__title">
        Filters
      </h2>

      <fieldset className="filters__group">
        <legend>Category</legend>
        <label className="radio">
          <input
            type="radio"
            name="category"
            checked={!value.categoryId}
            onChange={() => onChange({ ...value, categoryId: undefined })}
          />
          All
        </label>
        {categories.map((c) => (
          <label key={c.id} className="radio">
            <input
              type="radio"
              name="category"
              checked={value.categoryId === String(c.id)}
              onChange={() => onChange({ ...value, categoryId: String(c.id) })}
            />
            {c.name}
          </label>
        ))}
      </fieldset>

      <form
        className="filters__group"
        onSubmit={(e) => {
          e.preventDefault();
          onChange({ ...value, minPrice: min || undefined, maxPrice: max || undefined });
        }}
      >
        <fieldset>
          <legend>Price (₹)</legend>
          <div className="price-range">
            <label>
              <span className="sr-only">Minimum price</span>
              <input type="number" min={0} inputMode="numeric" placeholder="Min" value={min} onChange={(e) => setMin(e.target.value)} />
            </label>
            <span aria-hidden="true">–</span>
            <label>
              <span className="sr-only">Maximum price</span>
              <input type="number" min={0} inputMode="numeric" placeholder="Max" value={max} onChange={(e) => setMax(e.target.value)} />
            </label>
            <button type="submit" className="btn btn--ghost btn--sm">
              Go
            </button>
          </div>
        </fieldset>
      </form>

      <label className="checkbox">
        <input
          type="checkbox"
          checked={!!value.inStock}
          onChange={(e) => onChange({ ...value, inStock: e.target.checked || undefined })}
        />
        In stock only
      </label>

      <label className="filters__group select-label">
        Sort by
        <select value={value.sort ?? ""} onChange={(e) => onChange({ ...value, sort: e.target.value || undefined })}>
          <option value="">Popularity</option>
          <option value="price_asc">Price: low to high</option>
          <option value="price_desc">Price: high to low</option>
          <option value="rating">Customer rating</option>
          <option value="newest">Newest</option>
        </select>
      </label>
    </aside>
  );
}
