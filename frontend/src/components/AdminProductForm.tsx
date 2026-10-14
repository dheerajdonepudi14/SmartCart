"use client";

import { useState } from "react";
import { api, ApiError, errorMessage } from "@/lib/api";
import type { Category, Product, ProductInput } from "@/lib/types";

interface FormState {
  name: string;
  description: string;
  price: string;
  mrp: string;
  stock: string;
  categoryId: string;
  imageUrl: string;
  images: string;
  attributes: string;
  active: boolean;
}

function toForm(p?: Product): FormState {
  return {
    name: p?.name ?? "",
    description: p?.description ?? "",
    price: p ? String(p.price) : "",
    mrp: p?.mrp ? String(p.mrp) : "",
    stock: p ? String(p.stock) : "0",
    categoryId: p?.categoryId ? String(p.categoryId) : "",
    imageUrl: p?.imageUrl ?? "",
    images: (p?.images ?? []).join("\n"),
    attributes: Object.entries(p?.attributes ?? {})
      .map(([k, v]) => `${k}: ${v}`)
      .join("\n"),
    active: p?.active ?? true,
  };
}

export function parseAttributes(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of text.split("\n")) {
    const idx = line.indexOf(":");
    if (idx > 0) {
      const k = line.slice(0, idx).trim();
      const v = line.slice(idx + 1).trim();
      if (k && v) out[k] = v;
    }
  }
  return out;
}

export function AdminProductForm({
  product,
  categories,
  onSaved,
  onCancel,
}: {
  product?: Product;
  categories: Category[];
  onSaved: (p: Product) => void;
  onCancel: () => void;
}) {
  const [f, setF] = useState<FormState>(() => toForm(product));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const set = (k: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    setF((s) => ({ ...s, [k]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value }));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFieldErrors({});
    const body: ProductInput = {
      name: f.name.trim(),
      description: f.description.trim() || undefined,
      price: Number(f.price),
      mrp: f.mrp ? Number(f.mrp) : null,
      stock: Number(f.stock),
      categoryId: f.categoryId ? Number(f.categoryId) : null,
      imageUrl: f.imageUrl.trim() || undefined,
      images: f.images
        .split("\n")
        .map((s) => s.trim())
        .filter(Boolean),
      attributes: parseAttributes(f.attributes),
      active: f.active,
    };
    try {
      const saved = product
        ? await api<Product>(`/products/${product.id}`, { method: "PUT", body })
        : await api<Product>("/products", { method: "POST", body });
      onSaved(saved);
    } catch (err) {
      if (err instanceof ApiError) setFieldErrors(err.fieldErrors);
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const err = (k: string) => fieldErrors[k] && <span className="field__error">{fieldErrors[k]}</span>;

  return (
    <form className="card form-grid" onSubmit={submit} aria-labelledby="pf-heading">
      <h2 id="pf-heading" className="span-2">
        {product ? `Edit product #${product.id}` : "Add a product"}
      </h2>
      <label className="field span-2">
        <span>Name</span>
        <input required maxLength={200} value={f.name} onChange={set("name")} aria-required="true" />
        {err("name")}
      </label>
      <label className="field span-2">
        <span>Description</span>
        <textarea rows={3} maxLength={5000} value={f.description} onChange={set("description")} />
      </label>
      <label className="field">
        <span>Price (₹)</span>
        <input required type="number" min={0} step="0.01" value={f.price} onChange={set("price")} aria-required="true" />
        {err("price")}
      </label>
      <label className="field">
        <span>M.R.P. (₹, optional)</span>
        <input type="number" min={0} step="0.01" value={f.mrp} onChange={set("mrp")} />
        {err("mrp")}
      </label>
      <label className="field">
        <span>Stock</span>
        <input required type="number" min={0} step="1" value={f.stock} onChange={set("stock")} aria-required="true" />
        {err("stock")}
      </label>
      <label className="field">
        <span>Category</span>
        <select value={f.categoryId} onChange={set("categoryId")}>
          <option value="">— None —</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </label>
      <label className="field span-2">
        <span>Main image URL (https)</span>
        <input type="url" pattern="https://.*" value={f.imageUrl} onChange={set("imageUrl")} placeholder="https://images.unsplash.com/…" />
        {err("imageUrl")}
      </label>
      <label className="field span-2">
        <span>Gallery image URLs (one per line)</span>
        <textarea rows={2} value={f.images} onChange={set("images")} />
      </label>
      <label className="field span-2">
        <span>Specifications (one “key: value” per line)</span>
        <textarea rows={3} value={f.attributes} onChange={set("attributes")} placeholder={"brand: Acme\ncolor: Black"} />
      </label>
      <label className="checkbox span-2">
        <input type="checkbox" checked={f.active} onChange={set("active")} /> Visible in store
      </label>
      {error && (
        <p className="alert alert--error span-2" role="alert">
          {error}
        </p>
      )}
      <div className="span-2" style={{ display: "flex", gap: 8 }}>
        <button className="btn btn--primary" disabled={busy}>
          {busy ? "Saving…" : "Save product"}
        </button>
        <button type="button" className="btn btn--ghost" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
