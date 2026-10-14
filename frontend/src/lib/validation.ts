import type { Address } from "./types";

/** Client-side mirror of the server's address rules (Indian mobile number and PIN code). */
export function validateAddress(a: Address): Partial<Record<keyof Address, string>> {
  const e: Partial<Record<keyof Address, string>> = {};
  if (!a.fullName.trim()) e.fullName = "Enter the recipient's name";
  if (!/^[6-9]\d{9}$/.test(a.phone)) e.phone = "Enter a 10-digit mobile number";
  if (!a.line1.trim()) e.line1 = "Enter the street address";
  if (!a.city.trim()) e.city = "Enter the city";
  if (!a.state.trim()) e.state = "Choose a state";
  if (!/^[1-9]\d{5}$/.test(a.postalCode)) e.postalCode = "Enter a 6-digit PIN code";
  return e;
}
