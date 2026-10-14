import "server-only";
import type { Product } from "./types";

// Server components call the API over the internal network (e.g. http://backend:8080/api in Docker).
const INTERNAL = (process.env.API_INTERNAL_URL ?? process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api").replace(/\/+$/, "");

export async function fetchProduct(id: string): Promise<Product | null | "unavailable"> {
  if (!/^\d+$/.test(id)) return null;
  try {
    const res = await fetch(`${INTERNAL}/products/${id}`, { next: { revalidate: 60 } });
    if (res.status === 404) return null;
    if (!res.ok) return "unavailable";
    return (await res.json()) as Product;
  } catch {
    return "unavailable";
  }
}
