"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import { useCart } from "@/context/CartContext";
import { SearchBar } from "./SearchBar";

export function Navbar() {
  const { user, isAdmin, logout, ready } = useAuth();
  const { cart } = useCart();
  const router = useRouter();
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <header className="site-header">
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <nav className="nav container" aria-label="Main">
        <Link href="/" className="brand" aria-label="Smart Cart home">
          <span className="brand__mark" aria-hidden="true">
            S
          </span>
          <span className="brand__name">Smart Cart</span>
        </Link>

        <div className="nav__search">
          <Suspense fallback={<div className="search search--placeholder" />}>
            <SearchBar />
          </Suspense>
        </div>

        <div className="nav__actions">
          {ready && !user && (
            <Link href="/login" className="nav__link">
              Sign in
            </Link>
          )}
          {user && (
            <div className="account">
              <button
                type="button"
                className="nav__link account__toggle"
                aria-expanded={menuOpen}
                aria-controls="account-menu"
                onClick={() => setMenuOpen((o) => !o)}
              >
                Hi, {user.name.split(" ")[0]}
              </button>
              {menuOpen && (
                <ul id="account-menu" className="account__menu" onClick={() => setMenuOpen(false)}>
                  <li>
                    <Link href="/orders">Your orders</Link>
                  </li>
                  {isAdmin && (
                    <li>
                      <Link href="/admin">Admin dashboard</Link>
                    </li>
                  )}
                  <li>
                    <button
                      type="button"
                      onClick={async () => {
                        await logout();
                        router.push("/");
                      }}
                    >
                      Sign out
                    </button>
                  </li>
                </ul>
              )}
            </div>
          )}
          <Link href="/orders" className="nav__link hide-sm">
            Orders
          </Link>
          <Link href="/cart" className="cart-link" aria-label={`Cart, ${cart.itemCount} items`}>
            <svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">
              <path
                fill="currentColor"
                d="M7 18a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm10 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM7.2 14h9.9c.8 0 1.4-.4 1.8-1.1L22 6H5.2l-.9-2H1v2h2l3.6 7.6L5.2 16c-.1.3-.2.6-.2 1 0 1.1.9 2 2 2h12v-2H7.4c-.1 0-.2-.1-.2-.2l.9-1.8z"
              />
            </svg>
            <span className="cart-link__badge" aria-hidden="true">
              {cart.itemCount}
            </span>
            <span className="hide-sm" aria-hidden="true">
              Cart
            </span>
          </Link>
        </div>
      </nav>
    </header>
  );
}
