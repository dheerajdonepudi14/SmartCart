"use client";

import { useEffect, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { api, errorMessage, queryString } from "@/lib/api";
import type { Category, Page, Product } from "@/lib/types";
import { FilterPanel, type Filters } from "@/components/FilterPanel";
import { ProductCard } from "@/components/ProductCard";
import { Pagination } from "@/components/Pagination";
import { Recommendations } from "@/components/Recommendations";

const PAGE_SIZE = 12;

export function Catalog() {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const q = params.get("q") ?? "";
  const page = Math.max(0, Number(params.get("page") ?? 0) || 0);
  const filters: Filters = useMemo(
    () => ({
      categoryId: params.get("categoryId") ?? undefined,
      minPrice: params.get("minPrice") ?? undefined,
      maxPrice: params.get("maxPrice") ?? undefined,
      inStock: params.get("inStock") === "true" || undefined,
      sort: params.get("sort") ?? undefined,
    }),
    [params],
  );

  const [categories, setCategories] = useState<Category[]>([]);
  const [result, setResult] = useState<{ key: string; data?: Page<Product>; error?: string } | null>(null);

  useEffect(() => {
    api<Category[]>("/categories", { auth: false }).then(setCategories).catch(() => setCategories([]));
  }, []);

  const requestKey = queryString({ q, page, size: PAGE_SIZE, ...filters });
  useEffect(() => {
    const ctrl = new AbortController();
    api<Page<Product>>(`/products${requestKey}`, { auth: false, signal: ctrl.signal })
      .then((data) => setResult({ key: requestKey, data }))
      .catch((e) => {
        if (!ctrl.signal.aborted) setResult({ key: requestKey, error: errorMessage(e) });
      });
    return () => ctrl.abort();
  }, [requestKey]);

  const loading = result?.key !== requestKey;
  const data = loading ? undefined : result?.data;
  const error = loading ? undefined : result?.error;

  const update = (next: Filters & { page?: number }) => {
    router.push(
      `${pathname}${queryString({
        q: q || undefined,
        categoryId: next.categoryId,
        minPrice: next.minPrice,
        maxPrice: next.maxPrice,
        inStock: next.inStock ? true : undefined,
        sort: next.sort,
        page: next.page || undefined,
      })}`,
      { scroll: false },
    );
  };

  const categoryName = categories.find((c) => String(c.id) === filters.categoryId)?.name;

  return (
    <div className="container catalog">
      <FilterPanel
        key={`${filters.minPrice ?? ""}|${filters.maxPrice ?? ""}`}
        categories={categories}
        value={filters}
        onChange={(f) => update({ ...f, page: 0 })}
      />
      <section aria-labelledby="results-heading" aria-busy={loading}>
        <div className="catalog__head">
          <h2 id="results-heading">
            {q ? `Results for “${q}”` : categoryName ?? "All products"}
          </h2>
          {data && (
            <p className="muted small" role="status">
              {data.totalElements} item{data.totalElements === 1 ? "" : "s"}
            </p>
          )}
        </div>

        {error && (
          <p className="alert alert--error" role="alert">
            {error}
          </p>
        )}
        {loading && (
          <div className="grid" aria-hidden="true">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="skeleton" />
            ))}
          </div>
        )}
        {data && data.content.length === 0 && (
          <div className="empty card">
            <h3>No products match your search</h3>
            <p className="muted">Try different keywords or clear some filters.</p>
            <button type="button" className="btn btn--ghost" onClick={() => router.push("/")}>
              Clear search
            </button>
          </div>
        )}
        {data && data.content.length > 0 && (
          <div className="grid">
            {data.content.map((p) => (
              <ProductCard key={p.id} product={p} />
            ))}
          </div>
        )}
        {data && (
          <Pagination page={data.page} totalPages={data.totalPages} onChange={(p) => update({ ...filters, page: p })} />
        )}
        {!q && page === 0 && <Recommendations title="Top rated right now" />}
      </section>
    </div>
  );
}
