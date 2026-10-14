import type { Metadata } from "next";
import { AuthPage } from "@/components/AuthForm";

export const metadata: Metadata = { title: "Create account" };

export default function RegisterPage() {
  return <AuthPage mode="register" />;
}
