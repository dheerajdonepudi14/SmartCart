"use client";

import { useCallback, useEffect, useState } from "react";
import { api, errorMessage, queryString } from "@/lib/api";
import { formatINR } from "@/lib/format";
import type { Category, Page, Product } from "@/lib/types";
import { useAnnounce } from "@/context/AnnouncerContext";
import { AdminProductForm } from "./AdminProductForm";
import { Pagination } from "./Pagination";

export function AdminProductList() {
  const announce = useAnnounce();
  const [q, setQ] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<Product> | null>(null);
  const [categories, setCategories] = useState<Category[]>([]);
  const [editing, setEditing] = useState<Product | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [version, setVersion] = useState(0);

  const reload = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    api<Category[]>("/categories", { auth: false }).then(setCategories).catch(() => undefined);
  }, []);

  useEffect(() => {
    let active = true;
    api<Page<Product>>(`/admin/products${queryString({ q: query, page, size: 20 })}`)
      .then((d) => active && setData(d))
      .catch((e) => active && setError(errorMessage(e)));
    return () => {
      active = false;
    };
  }, [query, page, version]);

  const remove = async (p: Product) => {
    if (!window.confirm(`Hide “${p.name}” from the store?`)) return;
    try {
      await api<void>(`/products/${p.id}`, { method: "DELETE" });
      announce("Product hidden from the store");
      reload();
    } catch (e) {
      announce(errorMessage(e), "error");
    }
  };

  if (editing) {
    return (
      <AdminProductForm
        product={editing === "new" ? undefined : editing}
        categories={categories}
        onCancel={() => setEditing(null)}
        onSaved={(p) => {
          announce(`Saved ${p.name}`, "success");
          setEditing(null);
          reload();
        }}
      />
    );
  }

  return (
    <section aria-labelledby="ap-heading">
      <div className="catalog__head">
        <h2 id="ap-heading">Products</h2>
        <button type="button" className="btn btn--primary btn--sm" onClick={() => setEditing("new")}>
          + Add product
        </button>
      </div>
      <form
        role="search"
        style={{ display: "flex", gap: 8, marginBottom: 12 }}
        onSubmit={(e) => {
          e.preventDefault();
          setPage(0);
          setQuery(q);
        }}
      >
        <label className="sr-only" htmlFor="admin-q">
          Search products
        </label>
        <input id="admin-q" className="search__input card" style={{ padding: 10 }} value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search by name" />
        <button className="btn btn--ghost btn--sm">Search</button>
      </form>
      {error && <p className="alert alert--error">{error}</p>}
      <div className="table-wrap card" style={{ padding: 0 }}>
        <table className="table">
          <thead>
            <tr>
              <th scope="col">ID</th>
              <th scope="col">Name</th>
              <th scope="col">Price</th>
              <th scope="col">Stock</th>
              <th scope="col">Status</th>
              <th scope="col">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map((p) => (
              <tr key={p.id}>
                <td>{p.id}</td>
                <td>{p.name}</td>
                <td>{formatINR(p.price)}</td>
                <td>{p.stock}</td>
                <td>{p.active ? <span className="badge badge--ok">Active</span> : <span className="badge badge--muted">Hidden</span>}</td>
                <td style={{ whiteSpace: "nowrap" }}>
                  <button type="button" className="link-btn" onClick={() => setEditing(p)}>
                    Edit<span className="sr-only"> {p.name}</span>
                  </button>
                  {p.active && (
                    <button type="button" className="link-btn" onClick={() => remove(p)}>
                      Hide<span className="sr-only"> {p.name}</span>
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
    </section>
  );
}
