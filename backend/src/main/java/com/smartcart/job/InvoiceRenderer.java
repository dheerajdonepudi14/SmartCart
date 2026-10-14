package com.smartcart.job;

import com.smartcart.notification.EmailTemplates;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Renders a self-contained, printable HTML invoice. Pure, no Spring state. */
public final class InvoiceRenderer {
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    private InvoiceRenderer() {}

    public record Line(String name, int quantity, BigDecimal unitPrice, BigDecimal total) {}

    public record InvoiceData(long orderId, Instant orderedAt, String customerName, String customerEmail,
                              List<String> address, List<Line> lines, BigDecimal subtotal, BigDecimal shipping,
                              BigDecimal total, String gateway, String paymentId) {}

    public static String render(InvoiceData d) {
        StringBuilder rows = new StringBuilder();
        int n = 1;
        for (Line l : d.lines()) {
            rows.append("<tr><td>").append(n++).append("</td><td>").append(esc(l.name())).append("</td>")
                    .append("<td class=\"r\">").append(l.quantity()).append("</td>")
                    .append("<td class=\"r\">").append(money(l.unitPrice())).append("</td>")
                    .append("<td class=\"r\">").append(money(l.total())).append("</td></tr>");
        }
        String shipping = d.shipping().signum() == 0 ? "Free" : money(d.shipping());
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Invoice INV-%1$d</title>
                <style>
                  body{font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#111827;max-width:760px;margin:32px auto;padding:0 16px}
                  h1{color:#4f46e5;margin:0}.muted{color:#6b7280}
                  .head{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:28px}
                  .cols{display:flex;gap:48px;margin-bottom:24px}
                  table{width:100%%;border-collapse:collapse}th,td{padding:10px;border-bottom:1px solid #e5e7eb;text-align:left}
                  th{background:#4f46e5;color:#fff}.r{text-align:right}
                  .totals td{border:none;padding:6px 10px}.grand td{font-weight:700;font-size:1.1em;border-top:2px solid #111827}
                  .paid{display:inline-block;background:#dcfce7;color:#15803d;font-weight:700;padding:4px 10px;border-radius:999px}
                </style></head><body>
                <div class="head">
                  <div><h1>Smart Cart</h1><div class="muted">Tax invoice</div></div>
                  <div class="r"><strong>Invoice INV-%1$d</strong><br>Order #%1$d<br><span class="muted">%2$s</span><br><span class="paid">PAID</span></div>
                </div>
                <div class="cols">
                  <div><strong>Billed to</strong><br>%3$s<br><span class="muted">%4$s</span></div>
                  <div><strong>Ship to</strong><br>%5$s</div>
                  <div><strong>Payment</strong><br>%6$s<br><span class="muted">%7$s</span></div>
                </div>
                <table>
                  <thead><tr><th>#</th><th>Item</th><th class="r">Qty</th><th class="r">Unit price</th><th class="r">Amount</th></tr></thead>
                  <tbody>%8$s</tbody>
                </table>
                <table class="totals" style="margin-top:12px">
                  <tr><td class="r" style="width:80%%">Subtotal</td><td class="r">%9$s</td></tr>
                  <tr><td class="r">Shipping</td><td class="r">%10$s</td></tr>
                  <tr class="grand"><td class="r">Total paid</td><td class="r">%11$s</td></tr>
                </table>
                <p class="muted" style="margin-top:32px">Thank you for shopping with Smart Cart!</p>
                </body></html>
                """.formatted(d.orderId(), DATE.format(d.orderedAt()), esc(d.customerName()), esc(d.customerEmail()),
                String.join("<br>", d.address().stream().map(InvoiceRenderer::esc).toList()),
                esc(d.gateway()), esc(d.paymentId() == null ? "" : d.paymentId()), rows,
                money(d.subtotal()), shipping, money(d.total()));
    }

    private static String money(BigDecimal v) {
        return esc(EmailTemplates.money(v));
    }

    private static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }
}
