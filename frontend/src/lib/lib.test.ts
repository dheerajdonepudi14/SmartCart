import { describe, expect, it } from "vitest";
import { discountPercent, formatINR, shippingFor } from "@/lib/format";
import { toCart, upsertLine } from "@/lib/guestCart";
import { validateAddress } from "@/lib/validation";
import { queryString } from "@/lib/api";
import { safeNext } from "@/components/AuthForm";
import { parseAttributes } from "@/components/AdminProductForm";
import type { Product } from "@/lib/types";

const product = (over: Partial<Product> = {}): Product => ({
  id: 1,
  name: "Headphones",
  price: 250,
  mrp: 500,
  stock: 3,
  images: [],
  attributes: {},
  ratingAvg: 4.5,
  ratingCount: 10,
  active: true,
  ...over,
});

describe("format", () => {
  it("formats rupees with Indian grouping", () => {
    expect(formatINR(123456.5)).toBe("₹1,23,456.50");
  });
  it("computes discount percent", () => {
    expect(discountPercent(250, 500)).toBe(50);
    expect(discountPercent(500, 400)).toBe(0);
    expect(discountPercent(500)).toBe(0);
  });
  it("charges delivery below the free-shipping threshold", () => {
    expect(shippingFor(0)).toBe(0);
    expect(shippingFor(498)).toBe(49);
    expect(shippingFor(499)).toBe(0);
  });
});

describe("guest cart", () => {
  it("adds, caps at stock and removes lines", () => {
    let lines = upsertLine([], product(), 2, true);
    lines = upsertLine(lines, product(), 5, true); // capped by stock = 3
    expect(lines).toHaveLength(1);
    expect(lines[0].quantity).toBe(3);
    const cart = toCart(lines);
    expect(cart.subtotal).toBe(750);
    expect(cart.shippingFee).toBe(0);
    expect(cart.total).toBe(750);
    expect(upsertLine(lines, product(), 0, false)).toHaveLength(0);
  });
  it("adds delivery fee to small carts", () => {
    const cart = toCart(upsertLine([], product({ price: 100 }), 1, true));
    expect(cart.total).toBe(149);
  });
});

describe("address validation", () => {
  const ok = { fullName: "Ravi", phone: "9876543210", line1: "12 MG Road", city: "Tenali", state: "Andhra Pradesh", postalCode: "522201" };
  it("accepts a valid Indian address", () => {
    expect(validateAddress(ok)).toEqual({});
  });
  it("rejects bad phone and PIN", () => {
    const e = validateAddress({ ...ok, phone: "12345", postalCode: "022201" });
    expect(e.phone).toBeDefined();
    expect(e.postalCode).toBeDefined();
  });
});

describe("helpers", () => {
  it("builds query strings without empty values", () => {
    expect(queryString({ q: "tv", page: 0, empty: "", none: undefined })).toBe("?q=tv&page=0");
    expect(queryString({})).toBe("");
  });
  it("only allows relative post-login redirects", () => {
    expect(safeNext("/checkout")).toBe("/checkout");
    expect(safeNext("//evil.com")).toBe("/");
    expect(safeNext("https://evil.com")).toBe("/");
    expect(safeNext(null)).toBe("/");
  });
  it("parses admin specification lines", () => {
    expect(parseAttributes("brand: Acme\ncolor:Black\nbad line\nurl: https://x")).toEqual({
      brand: "Acme",
      color: "Black",
      url: "https://x",
    });
  });
});
