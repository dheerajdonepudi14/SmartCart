package com.smartcart.notification;

import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/** Renders transactional emails as an HTML body plus a plain-text alternative. Pure, no Spring state. */
public final class EmailTemplates {
    private EmailTemplates() {}

    public record Email(String subject, String html, String text) {}

    public enum OrderEmailKind {
        PAYMENT_RECEIVED, PAYMENT_FAILED, SHIPPED, DELIVERED, CANCELLED
    }

    public record Line(String name, int quantity, BigDecimal total) {}

    public record OrderData(long orderId, String customerName, List<Line> lines, BigDecimal subtotal,
                            BigDecimal shipping, BigDecimal total, List<String> address, String message,
                            String orderUrl, String payUrl) {}

    public static Email welcome(String name, String shopUrl) {
        String subject = "Welcome to Smart Cart";
        String html = layout(subject,
                p("Hi " + esc(name) + ",")
                        + p("Your Smart Cart account is ready. Browse the catalogue, add what you like to your cart"
                        + " and check out securely with UPI, cards or net banking.")
                        + button("Start shopping", shopUrl));
        String text = "Hi " + name + ",\n\nYour Smart Cart account is ready.\n\nStart shopping: " + shopUrl + "\n";
        return new Email(subject, html, text);
    }

    public static Email order(OrderEmailKind kind, OrderData d) {
        String id = "#" + d.orderId();
        String subject;
        String intro;
        String ctaLabel = "View order";
        String ctaUrl = d.orderUrl();
        switch (kind) {
            case PAYMENT_RECEIVED -> {
                subject = "Order " + id + " confirmed - payment received";
                intro = "Thanks for your order! We've received your payment of " + money(d.total())
                        + " and are getting your items ready.";
            }
            case PAYMENT_FAILED -> {
                subject = "Payment failed for order " + id;
                intro = "Your payment for order " + id + " didn't go through"
                        + (d.message() == null ? "." : ": " + d.message() + ".")
                        + " No money was taken. Your items are reserved for a short while, so you can try again.";
                ctaLabel = "Retry payment";
                ctaUrl = d.payUrl();
            }
            case SHIPPED -> {
                subject = "Order " + id + " has shipped";
                intro = "Good news! Your order " + id + " is on its way.";
            }
            case DELIVERED -> {
                subject = "Order " + id + " was delivered";
                intro = "Your order " + id + " has been delivered. We hope you enjoy it, and we'd love a review.";
            }
            case CANCELLED -> {
                subject = "Order " + id + " was cancelled";
                intro = "Your order " + id + " has been cancelled"
                        + (d.message() == null ? "." : ": " + d.message() + ".");
            }
            default -> throw new IllegalArgumentException(kind.name());
        }

        String html = layout(subject,
                p("Hi " + esc(d.customerName()) + ",")
                        + p(esc(intro))
                        + itemsTable(d)
                        + (d.address().isEmpty() ? "" : p("<strong>Shipping to</strong><br>"
                        + String.join("<br>", d.address().stream().map(EmailTemplates::esc).toList())))
                        + button(ctaLabel, ctaUrl));

        StringBuilder text = new StringBuilder("Hi ").append(d.customerName()).append(",\n\n").append(intro).append("\n\n");
        d.lines().forEach(l -> text.append("  ").append(l.quantity()).append(" x ").append(l.name())
                .append("  ").append(money(l.total())).append('\n'));
        text.append("\n  Subtotal: ").append(money(d.subtotal()))
                .append("\n  Shipping: ").append(shippingLabel(d.shipping()))
                .append("\n  Total:    ").append(money(d.total())).append("\n\n")
                .append(ctaLabel).append(": ").append(ctaUrl).append('\n');
        return new Email(subject, html, text.toString());
    }

    public static String money(BigDecimal amount) {
        return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).format(amount);
    }

    private static String shippingLabel(BigDecimal shipping) {
        return shipping.signum() == 0 ? "Free" : money(shipping);
    }

    private static String itemsTable(OrderData d) {
        StringBuilder rows = new StringBuilder();
        for (Line l : d.lines()) {
            rows.append("<tr><td style=\"padding:6px 0\">").append(esc(l.name()))
                    .append(" <span style=\"color:#6b7280\">&times; ").append(l.quantity()).append("</span></td>")
                    .append("<td style=\"padding:6px 0;text-align:right\">").append(esc(money(l.total())))
                    .append("</td></tr>");
        }
        return "<table role=\"presentation\" width=\"100%\" style=\"border-collapse:collapse;margin:16px 0;"
                + "border-top:1px solid #e5e7eb;border-bottom:1px solid #e5e7eb\">" + rows
                + summaryRow("Subtotal", money(d.subtotal()), false)
                + summaryRow("Shipping", shippingLabel(d.shipping()), false)
                + summaryRow("Total", money(d.total()), true)
                + "</table>";
    }

    private static String summaryRow(String label, String value, boolean bold) {
        String weight = bold ? "font-weight:700;" : "color:#4b5563;";
        return "<tr><td style=\"padding:4px 0;" + weight + "\">" + label + "</td>"
                + "<td style=\"padding:4px 0;text-align:right;" + weight + "\">" + esc(value) + "</td></tr>";
    }

    private static String p(String innerHtml) {
        return "<p style=\"margin:0 0 14px;line-height:1.5\">" + innerHtml + "</p>";
    }

    private static String button(String label, String url) {
        return "<p style=\"margin:24px 0 8px\"><a href=\"" + esc(url) + "\" style=\"background:#4f46e5;color:#ffffff;"
                + "padding:12px 20px;border-radius:6px;text-decoration:none;font-weight:600;display:inline-block\">"
                + esc(label) + "</a></p>";
    }

    private static String layout(String title, String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + esc(title) + "</title></head>"
                + "<body style=\"margin:0;background:#f3f4f6;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,"
                + "sans-serif;color:#111827;font-size:15px\">"
                + "<table role=\"presentation\" width=\"100%\" style=\"padding:24px 12px\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" style=\"max-width:560px;background:#ffffff;"
                + "border-radius:8px;padding:28px\"><tr><td>"
                + "<div style=\"font-size:20px;font-weight:700;margin-bottom:20px\">Smart Cart</div>"
                + body
                + "<p style=\"margin:24px 0 0;color:#9ca3af;font-size:12px\">You're receiving this because you have"
                + " an account at Smart Cart.</p>"
                + "</td></tr></table></td></tr></table></body></html>";
    }

    private static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }
}
