import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { fetchProduct } from "@/lib/serverApi";
import { ProductDetail } from "./ProductDetail";

type Params = { params: Promise<{ id: string }> };

export async function generateMetadata({ params }: Params): Promise<Metadata> {
  const { id } = await params;
  const p = await fetchProduct(id);
  if (!p || p === "unavailable") return { title: "Product" };
  return {
    title: p.name,
    description: p.description?.slice(0, 160),
    openGraph: { title: p.name, description: p.description?.slice(0, 160), images: p.imageUrl ? [p.imageUrl] : [] },
  };
}

/** Server-rendered for SEO; interactive parts (cart, reviews, recommendations) hydrate on the client. */
export default async function ProductPage({ params }: Params) {
  const { id } = await params;
  const product = await fetchProduct(id);
  if (product === null) notFound();
  if (product === "unavailable") {
    return (
      <div className="container page">
        <h1>We couldn’t load this product</h1>
        <p className="muted">The store is temporarily unavailable. Please try again in a moment.</p>
      </div>
    );
  }

  const jsonLd = {
    "@context": "https://schema.org",
    "@type": "Product",
    name: product.name,
    description: product.description,
    image: product.images.length ? product.images : product.imageUrl ? [product.imageUrl] : undefined,
    offers: {
      "@type": "Offer",
      priceCurrency: "INR",
      price: product.price,
      availability: product.stock > 0 ? "https://schema.org/InStock" : "https://schema.org/OutOfStock",
    },
    ...(product.ratingCount > 0 && {
      aggregateRating: { "@type": "AggregateRating", ratingValue: product.ratingAvg, reviewCount: product.ratingCount },
    }),
  };

  return (
    <>
      <script
        type="application/ld+json"
        // JSON.stringify output with "<" escaped cannot break out of the script element.
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd).replace(/</g, "\\u003c") }}
      />
      <ProductDetail product={product} />
    </>
  );
}
