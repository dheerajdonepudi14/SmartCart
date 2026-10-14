// Mirrors the Spring Boot DTOs. Money arrives as JSON numbers in rupees (BigDecimal).

export type Role = "ROLE_USER" | "ROLE_ADMIN";

export interface User {
  id: number;
  name: string;
  email: string;
  role: Role;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

export interface Category {
  id: number;
  name: string;
  slug: string;
}

export interface Product {
  id: number;
  name: string;
  description?: string;
  price: number;
  mrp?: number;
  stock: number;
  categoryId?: number;
  categoryName?: string;
  imageUrl?: string;
  images: string[];
  attributes: Record<string, string>;
  ratingAvg: number;
  ratingCount: number;
  active: boolean;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CartLine {
  productId: number;
  name: string;
  imageUrl?: string;
  price: number;
  quantity: number;
  stock: number;
  available: boolean;
  lineTotal: number;
}

export interface Cart {
  items: CartLine[];
  itemCount: number;
  subtotal: number;
  shippingFee: number;
  total: number;
}

export type Gateway = "RAZORPAY" | "PHONEPE";

export type OrderStatus = "PENDING_PAYMENT" | "PAID" | "SHIPPED" | "DELIVERED" | "CANCELLED";

export interface Address {
  fullName: string;
  phone: string;
  line1: string;
  line2?: string;
  city: string;
  state: string;
  postalCode: string;
}

export interface OrderItem {
  productId: number;
  name: string;
  imageUrl?: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

export type PaymentStatus = "CREATED" | "SUCCEEDED" | "FAILED" | "REFUND_PENDING" | "REFUNDED";

export interface PaymentInfo {
  id: number;
  gateway: Gateway;
  status: PaymentStatus;
  gatewayOrderId: string;
  gatewayPaymentId?: string;
  method?: string;
  amountPaise: number;
  refundId?: string;
  failureReason?: string;
  createdAt: string;
}

export interface OrderSummary {
  id: number;
  status: OrderStatus;
  totalAmount: number;
  currency: string;
  itemCount: number;
  firstItemName?: string;
  firstItemImage?: string;
  paymentGateway: Gateway;
  createdAt: string;
}

export interface OrderDetail {
  id: number;
  status: OrderStatus;
  subtotal: number;
  shippingFee: number;
  totalAmount: number;
  currency: string;
  paymentGateway: Gateway;
  address: Address;
  items: OrderItem[];
  latestPayment?: PaymentInfo;
  customerEmail?: string;
  createdAt: string;
  updatedAt: string;
}

export interface OrderStatusEvent {
  orderId: number;
  userId: number;
  status: OrderStatus;
  paymentStatus?: string;
  message?: string;
  at: string;
}

export interface RazorpayOptions {
  keyId: string;
  razorpayOrderId: string;
  amount: number;
  currency: string;
  name: string;
  description: string;
  prefill: { name?: string; email?: string; contact?: string };
}

export interface CheckoutResponse {
  orderId: number;
  orderStatus: OrderStatus;
  gateway: Gateway;
  amountPaise: number;
  currency: string;
  razorpay?: RazorpayOptions;
  redirectUrl?: string;
  payUrl: string;
  /** True when the Idempotency-Key matched an earlier checkout (no new order was created). */
  replayed: boolean;
}

export interface PaymentResult {
  orderId: number;
  orderStatus: OrderStatus;
  paymentStatus: PaymentStatus;
  gatewayPaymentId?: string;
  message?: string;
}

export interface PaymentConfig {
  /** mode: live | test | sandbox | production | not_configured */
  gateways: Record<Gateway, { available: boolean; mode: string }>;
  razorpayKeyId?: string;
}

export interface StockLevel {
  productId: number;
  stock: number;
}

export interface Review {
  id: number;
  productId: number;
  userName: string;
  rating: number;
  title?: string;
  comment?: string;
  verifiedPurchase: boolean;
  createdAt: string;
}

export interface ProductInput {
  name: string;
  description?: string;
  price: number;
  mrp?: number | null;
  stock: number;
  categoryId?: number | null;
  imageUrl?: string;
  images?: string[];
  attributes?: Record<string, string>;
  active?: boolean;
}
