import type { Metadata } from "next";
import { AuthPage } from "@/components/AuthForm";

export const metadata: Metadata = { title: "Sign in" };

export default function LoginPage() {
  return <AuthPage mode="login" />;
}
