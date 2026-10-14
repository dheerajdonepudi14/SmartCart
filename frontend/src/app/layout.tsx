import type { Metadata, Viewport } from "next";
import "./globals.css";
import { Providers } from "@/context/Providers";
import { Navbar } from "@/components/Navbar";
import { Footer } from "@/components/Footer";

export const metadata: Metadata = {
  title: { default: "Smart Cart – Shop smarter", template: "%s | Smart Cart" },
  description: "Shop electronics, fashion, home essentials and more with fast delivery and secure UPI, card and wallet payments.",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: "#ea580c",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en-IN">
      <body>
        <Providers>
          <Navbar />
          <main id="main" tabIndex={-1}>
            {children}
          </main>
          <Footer />
        </Providers>
      </body>
    </html>
  );
}
