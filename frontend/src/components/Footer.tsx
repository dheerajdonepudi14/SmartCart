import Link from "next/link";

export function Footer() {
  return (
    <footer className="site-footer">
      <div className="container footer__grid">
        <div>
          <p className="brand brand--footer">
            <span className="brand__mark" aria-hidden="true">
              S
            </span>
            <span className="brand__name">Smart Cart</span>
          </p>
          <p className="muted">Everyday essentials, delivered across India.</p>
        </div>
        <nav aria-label="Footer">
          <ul className="footer__links">
            <li>
              <Link href="/">Shop all</Link>
            </li>
            <li>
              <Link href="/orders">Track orders</Link>
            </li>
            <li>
              <Link href="/cart">Cart</Link>
            </li>
          </ul>
        </nav>
        <div className="muted small">
          <p>Secure payments via Razorpay and PhonePe.</p>
          <p>Free delivery on orders above ₹499.</p>
        </div>
      </div>
      <p className="container small muted footer__legal">© {new Date().getFullYear()} Smart Cart</p>
    </footer>
  );
}
