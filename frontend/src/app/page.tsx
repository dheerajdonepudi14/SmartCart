import { Suspense } from "react";
import { Catalog } from "./Catalog";

export default function HomePage() {
  return (
    <>
      <section className="hero">
        <div className="container">
          <h1>Shop smarter, pay your way</h1>
          <p>
            Handpicked electronics, fashion and home essentials. Pay instantly with UPI, cards, net banking or
            wallets through Razorpay or PhonePe — free delivery above ₹499.
          </p>
        </div>
      </section>
      <Suspense
        fallback={
          <div className="container catalog">
            <div className="skeleton" />
            <div className="skeleton" />
          </div>
        }
      >
        <Catalog />
      </Suspense>
    </>
  );
}
