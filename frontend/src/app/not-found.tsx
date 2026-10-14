import Link from "next/link";

export default function NotFound() {
  return (
    <div className="container page empty">
      <h1>Page not found</h1>
      <p className="muted">The page you’re looking for doesn’t exist or has moved.</p>
      <Link href="/" className="btn btn--primary">
        Back to shopping
      </Link>
    </div>
  );
}
