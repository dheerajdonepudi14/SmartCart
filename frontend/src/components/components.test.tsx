import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { Product } from "@/lib/types";

const add = vi.fn(async () => {});
vi.mock("@/context/CartContext", () => ({ useCart: () => ({ add }) }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import { ProductCard } from "./ProductCard";
import { OrderTracker } from "./StatusBadge";

const product: Product = {
  id: 7,
  name: "Portable Speaker",
  price: 2799,
  mrp: 3999,
  stock: 5,
  imageUrl: "https://example.com/speaker.jpg",
  images: [],
  attributes: {},
  ratingAvg: 4.2,
  ratingCount: 18,
  active: true,
};

describe("ProductCard", () => {
  beforeEach(() => add.mockClear());

  it("shows name, price, discount and rating", () => {
    render(<ProductCard product={product} />);
    expect(screen.getByRole("heading", { name: "Portable Speaker" })).toBeInTheDocument();
    expect(screen.getByText("₹2,799.00")).toBeInTheDocument();
    expect(screen.getByText("30% off")).toBeInTheDocument();
    expect(screen.getByLabelText(/Rated 4.2 out of 5, 18 reviews/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Portable Speaker/ })).toHaveAttribute("href", "/products/7");
  });

  it("adds to cart when the button is clicked", async () => {
    render(<ProductCard product={product} />);
    await userEvent.click(screen.getByRole("button", { name: "Add Portable Speaker to cart" }));
    expect(add).toHaveBeenCalledWith(product, 1);
  });

  it("disables the button when out of stock", () => {
    render(<ProductCard product={{ ...product, stock: 0 }} />);
    expect(screen.getByRole("button", { name: /out of stock/i })).toBeDisabled();
  });
});

describe("OrderTracker", () => {
  it("marks the current step for screen readers", () => {
    render(<OrderTracker status="SHIPPED" />);
    expect(screen.getByText("Shipped").closest("li")).toHaveAttribute("aria-current", "step");
  });
  it("shows a cancellation notice", () => {
    render(<OrderTracker status="CANCELLED" />);
    expect(screen.getByText(/cancelled/i)).toBeInTheDocument();
  });
});
