#!/usr/bin/env python3
"""Generates the Smart Cart Postman collection (v2.1) and local environment.

Run:  python3 postman/build_collection.py
Out:  postman/SmartCart.postman_collection.json
      postman/SmartCart-Local.postman_environment.json
"""
import json
import os
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
NS = uuid.UUID("5d7e9c1a-6a1b-4f8e-9a8f-3c2b1e0d9f7a")


def uid(name):
    return str(uuid.uuid5(NS, name))


# ----------------------------------------------------------------------------- shared script snippets

JWT_HELPERS = r"""
// Decodes the header and payload of a JWT (no signature check - the server does that).
function decodeJwt(token) {
    const cryptoJs = require('crypto-js');
    const part = (s) => {
        const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4);
        return JSON.parse(cryptoJs.enc.Utf8.stringify(cryptoJs.enc.Base64.parse(b64)));
    };
    const [h, p] = token.split('.');
    return { header: part(h), payload: part(p) };
}
""".strip()

SAVE_TOKEN = JWT_HELPERS + r"""

function saveToken(varName, body) {
    pm.environment.set(varName, body.accessToken);
    const jwt = decodeJwt(body.accessToken);
    pm.environment.set(varName + 'Payload', JSON.stringify(jwt.payload));
    pm.environment.set(varName + 'ExpiresAt', new Date(jwt.payload.exp * 1000).toISOString());
    console.log('JWT header', jwt.header);
    console.log('JWT payload', jwt.payload);
    const template = `
      <style>body{font-family:system-ui;padding:8px} table{border-collapse:collapse} td,th{border:1px solid #ddd;padding:6px 10px;text-align:left}
      code{word-break:break-all;display:block;background:#fff7ed;padding:8px;border-radius:6px}</style>
      <h3>Access token (JWT, {{alg}})</h3><code>{{token}}</code>
      <h4>Claims</h4><table>{{#each claims}}<tr><th>{{k}}</th><td>{{v}}</td></tr>{{/each}}</table>
      <p>Refresh token: HttpOnly cookie <b>sc_refresh</b> (see the Cookies tab / cookie manager).</p>`;
    const claims = Object.entries(jwt.payload).map(([k, v]) => ({
        k, v: (k === 'iat' || k === 'exp') ? v + '  (' + new Date(v * 1000).toISOString() + ')' : v }));
    pm.visualizer.set(template, { token: body.accessToken, alg: jwt.header.alg, claims });
    return jwt;
}
""".strip()


def auto_login(token_var, email_var, password_var):
    """Folder pre-request: signs in first when the token is missing or about to expire."""
    return JWT_HELPERS + f"""

const token = pm.environment.get('{token_var}');
let refresh = true;
if (token) {{
    try {{ refresh = decodeJwt(token).payload.exp * 1000 < Date.now() + 30000; }} catch (e) {{ refresh = true; }}
}}
const email = pm.environment.get('{email_var}');
const password = pm.environment.get('{password_var}');
if (refresh && email && password) {{
    pm.sendRequest({{
        url: pm.environment.get('baseUrl') + '/auth/login',
        method: 'POST',
        header: {{ 'Content-Type': 'application/json' }},
        body: {{ mode: 'raw', raw: JSON.stringify({{ email, password }}) }}
    }}, (err, res) => {{
        if (err || res.code !== 200) {{
            console.warn('Auto-login for {email_var} failed', err || res.code);
            return;
        }}
        pm.environment.set('{token_var}', res.json().accessToken);
        console.log('Auto-login OK, fresh {token_var} stored');
    }});
}}
"""


# ----------------------------------------------------------------------------- builders

def url(raw):
    """Postman URL object from a raw string such as {{baseUrl}}/products?page=0."""
    base, _, query = raw.partition("?")
    host_and_path = base.split("/")
    u = {"raw": raw, "host": [host_and_path[0]], "path": [p for p in host_and_path[1:] if p]}
    if query:
        u["query"] = [{"key": k, "value": v} for k, _, v in (q.partition("=") for q in query.split("&"))]
    return u


def req(name, method, raw_url, *, body=None, tests=None, pre=None, auth=None, headers=None, desc=None):
    item = {
        "id": uid(name),
        "name": name,
        "request": {
            "method": method,
            "header": [{"key": "Accept", "value": "application/json"}] + (headers or []),
            "url": url(raw_url),
        },
        "event": [],
    }
    if desc:
        item["request"]["description"] = desc
    if body is not None:
        item["request"]["header"].append({"key": "Content-Type", "value": "application/json"})
        item["request"]["body"] = {"mode": "raw", "raw": json.dumps(body, indent=2) if not isinstance(body, str) else body,
                                   "options": {"raw": {"language": "json"}}}
    if auth is not None:
        item["request"]["auth"] = auth
    if pre:
        item["event"].append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": pre.strip().split("\n")}})
    if tests:
        item["event"].append({"listen": "test", "script": {"type": "text/javascript", "exec": tests.strip().split("\n")}})
    return item


def folder(name, items, *, desc=None, auth=None, pre=None):
    f = {"id": uid("folder:" + name), "name": name, "item": items, "event": []}
    if desc:
        f["description"] = desc
    if auth is not None:
        f["auth"] = auth
    if pre:
        f["event"].append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": pre.strip().split("\n")}})
    return f


NOAUTH = {"type": "noauth"}


def bearer(var):
    return {"type": "bearer", "bearer": [{"key": "token", "value": "{{" + var + "}}", "type": "string"}]}


RZP_BASIC = {"type": "basic", "basic": [
    {"key": "username", "value": "{{razorpayKeyId}}", "type": "string"},
    {"key": "password", "value": "{{razorpayKeySecret}}", "type": "string"}]}

ADDRESS = {"fullName": "{{userName}}", "phone": "9876543210", "line1": "12 MG Road", "line2": "Near Clock Tower",
           "city": "Tenali", "state": "Andhra Pradesh", "postalCode": "522201"}

STATUS = lambda *codes: (
    f"pm.test('HTTP status is {' or '.join(map(str, codes))}', () => "
    f"pm.expect(pm.response.code).to.be.oneOf([{', '.join(map(str, codes))}]));")

# ----------------------------------------------------------------------------- 00 setup

setup = folder("00 Setup & health", [
    req("Health (actuator)", "GET", "{{serverUrl}}/actuator/health", auth=NOAUTH, tests=STATUS(200) + """
pm.test('Backend is UP', () => pm.expect(pm.response.json().status).to.eql('UP'));
"""),
    req("Payment gateway config", "GET", "{{baseUrl}}/payments/config", auth=NOAUTH, tests=STATUS(200) + """
const cfg = pm.response.json();
console.log('Gateways', cfg.gateways);
pm.test('Razorpay is configured on the server (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET)', () =>
    pm.expect(cfg.gateways.RAZORPAY.available, 'Set Razorpay keys in the backend .env and restart').to.be.true);
pm.test('Razorpay mode is test or live', () => pm.expect(cfg.gateways.RAZORPAY.mode).to.be.oneOf(['test', 'live']));
if (cfg.razorpayKeyId) {
    pm.environment.set('razorpayKeyId', cfg.razorpayKeyId);
}
"""),
], desc="Checks the API is up and that real Razorpay keys are loaded. There is no simulator: "
        "if Razorpay is not configured, checkout returns 503.")

# ----------------------------------------------------------------------------- 01 auth

auth = folder("01 Auth (JWT)", [
    req("Register shopper", "POST", "{{baseUrl}}/auth/register", auth=NOAUTH,
        body={"name": "{{userName}}", "email": "{{userEmail}}", "password": "{{userPassword}}"},
        pre="""
if (!pm.environment.get('userEmail')) {
    pm.environment.set('userEmail', 'shopper.' + Date.now() + '@example.com');
}
""",
        tests=SAVE_TOKEN + "\n" + STATUS(200, 409) + """
if (pm.response.code === 200) {
    const jwt = saveToken('accessToken', pm.response.json());
    pm.test('JWT carries role ROLE_USER', () => pm.expect(jwt.payload.role).to.eql('ROLE_USER'));
    pm.test('Refresh token set as HttpOnly cookie sc_refresh', () => pm.expect(pm.cookies.has('sc_refresh')).to.be.true);
} else {
    console.log('Account already exists - use "Login shopper".');
}
"""),
    req("Login shopper", "POST", "{{baseUrl}}/auth/login", auth=NOAUTH,
        body={"email": "{{userEmail}}", "password": "{{userPassword}}"},
        tests=SAVE_TOKEN + "\n" + STATUS(200) + """
const body = pm.response.json();
const jwt = saveToken('accessToken', body);
pm.test('Token type is Bearer', () => pm.expect(body.tokenType).to.eql('Bearer'));
pm.test('JWT has header.payload.signature', () => pm.expect(body.accessToken.split('.')).to.have.length(3));
pm.test('Signed with HS256', () => pm.expect(jwt.header.alg).to.eql('HS256'));
pm.test('Claims: sub, email, role, iat, exp, jti, iss', () =>
    pm.expect(jwt.payload).to.include.keys('sub', 'email', 'role', 'iat', 'exp', 'jti', 'iss'));
pm.test('expiresIn matches exp - iat (access token lifetime)', () =>
    pm.expect(jwt.payload.exp - jwt.payload.iat).to.eql(body.expiresIn));
pm.test('Refresh token cookie sc_refresh is set', () => pm.expect(pm.cookies.has('sc_refresh')).to.be.true);
"""),
    req("Current user (me)", "GET", "{{baseUrl}}/auth/me", auth=bearer("accessToken"), tests=STATUS(200) + """
pm.test('Token identifies the shopper', () => pm.expect(pm.response.json().email).to.eql(pm.environment.get('userEmail')));
"""),
    req("Refresh access token (rotates cookie)", "POST", "{{baseUrl}}/auth/refresh", auth=NOAUTH,
        desc="Sends the HttpOnly sc_refresh cookie from Postman's cookie jar. The server revokes it and "
             "returns a new access token plus a new refresh cookie. Replaying an old refresh cookie revokes the session.",
        pre="pm.environment.set('previousAccessToken', pm.environment.get('accessToken'));",
        tests=SAVE_TOKEN + "\n" + STATUS(200) + """
saveToken('accessToken', pm.response.json());
pm.test('A new access token was issued', () =>
    pm.expect(pm.response.json().accessToken).to.not.eql(pm.environment.get('previousAccessToken')));
"""),
    req("Login with wrong password → 401", "POST", "{{baseUrl}}/auth/login", auth=NOAUTH,
        body={"email": "{{userEmail}}", "password": "definitely-wrong-1"}, tests=STATUS(401)),
    req("Cart without token → 401", "GET", "{{baseUrl}}/cart", auth=NOAUTH, tests=STATUS(401)),
    req("Cart with tampered token → 401", "GET", "{{baseUrl}}/cart", auth=NOAUTH,
        headers=[{"key": "Authorization", "value": "Bearer {{accessToken}}x"}], tests=STATUS(401)),
], desc="Access tokens are HS256 JWTs (15 min) returned in the JSON body and stored in the "
        "`accessToken` environment variable; decoded claims are shown in the Visualize tab and saved to "
        "`accessTokenPayload`. The refresh token is an HttpOnly cookie (`sc_refresh`) kept by Postman's cookie jar.")

# ----------------------------------------------------------------------------- 02 catalog

catalog = folder("02 Catalog (public)", [
    req("List products (paged, sorted)", "GET", "{{baseUrl}}/products?page=0&size=12&sort=price_asc&inStock=true",
        auth=NOAUTH, tests=STATUS(200) + """
const page = pm.response.json();
pm.test('Paged response', () => pm.expect(page).to.include.keys('content', 'page', 'size', 'totalElements', 'totalPages'));
pm.test('At least one product in stock', () => pm.expect(page.content.length).to.be.above(0));
const p = page.content.find(x => x.stock > 0);
pm.environment.set('productId', p.id);
console.log('Using product', p.id, p.name, p.price, 'stock', p.stock);
"""),
    req("Search products", "GET", "{{baseUrl}}/search?query=headphones", auth=NOAUTH, tests=STATUS(200)),
    req("Autocomplete suggestions", "GET", "{{baseUrl}}/search/suggest?q=wa", auth=NOAUTH, tests=STATUS(200) + """
pm.test('Returns suggestions array', () => pm.expect(pm.response.json().suggestions).to.be.an('array'));
"""),
    req("Product details", "GET", "{{baseUrl}}/products/{{productId}}", auth=NOAUTH, tests=STATUS(200) + """
pm.test('Has price and stock', () => pm.expect(pm.response.json()).to.include.keys('price', 'stock', 'images', 'attributes'));
"""),
    req("Categories", "GET", "{{baseUrl}}/categories", auth=NOAUTH, tests=STATUS(200)),
    req("Recommendations for product", "GET", "{{baseUrl}}/recommendations?productId={{productId}}&limit=6",
        auth=NOAUTH, tests=STATUS(200)),
    req("Reviews for product", "GET", "{{baseUrl}}/reviews?productId={{productId}}", auth=NOAUTH, tests=STATUS(200)),
], desc="Live inventory (real-time stock) is a Server-Sent Events stream: "
        "`curl -N {{baseUrl}}/products/stock/stream` - it stays open, so it is not part of a collection run.")

# ----------------------------------------------------------------------------- 03 cart

cart = folder("03 Cart", [
    req("Empty the cart", "DELETE", "{{baseUrl}}/cart", tests=STATUS(200)),
    req("Add product to cart", "POST", "{{baseUrl}}/cart", body={"productId": "{{productId}}", "quantity": 1},
        tests=STATUS(200) + """
const c = pm.response.json();
pm.test('Cart has the item', () => pm.expect(c.items.map(i => i.productId)).to.include(Number(pm.environment.get('productId'))));
pm.test('Totals add up (subtotal + delivery = total)', () =>
    pm.expect(Math.round((c.subtotal + c.shippingFee) * 100)).to.eql(Math.round(c.total * 100)));
"""),
    req("Get cart", "GET", "{{baseUrl}}/cart", tests=STATUS(200)),
], pre=auto_login("accessToken", "userEmail", "userPassword"))

# ----------------------------------------------------------------------------- 04 checkout

checkout = folder("04 Checkout & Razorpay (no duplicates)", [
    req("Checkout → creates order + Razorpay order", "POST", "{{baseUrl}}/checkout",
        headers=[{"key": "Idempotency-Key", "value": "{{idempotencyKey}}"}],
        body={"gateway": "RAZORPAY", "address": ADDRESS},
        pre="""
// A new key per checkout attempt. Re-sending THIS request with the same key never creates a second order.
pm.environment.set('idempotencyKey', pm.variables.replaceIn('{{$guid}}'));
""",
        tests=STATUS(200) + """
const r = pm.response.json();
pm.environment.set('orderId', r.orderId);
pm.environment.set('amountPaise', r.amountPaise);
pm.environment.set('payUrl', r.payUrl);
pm.environment.set('razorpayOrderId', r.razorpay.razorpayOrderId);
pm.test('Order awaits payment', () => pm.expect(r.orderStatus).to.eql('PENDING_PAYMENT'));
pm.test('Real Razorpay order id (order_...)', () => pm.expect(r.razorpay.razorpayOrderId).to.match(/^order_/));
pm.test('Checkout uses your Razorpay key', () => pm.expect(r.razorpay.keyId).to.match(/^rzp_(test|live)_/));
pm.test('Amount is in paise', () => pm.expect(r.amountPaise).to.eql(r.razorpay.amount));
pm.test('First request is not a replay', () => pm.expect(r.replayed).to.be.false);
console.log('Open this URL in your browser to pay with Razorpay:', r.payUrl);
pm.visualizer.set(`<div style="font-family:system-ui;padding:12px">
  <h3>Order #{{orderId}} created – {{amount}}</h3>
  <p>Razorpay order <code>{{rzp}}</code></p>
  <p><b>Pay it in the browser:</b> <a href="{{payUrl}}" target="_blank">{{payUrl}}</a></p>
  <p>Test mode: UPI <code>success@razorpay</code> or a Razorpay test card. Then run folder 05.</p></div>`,
  { orderId: r.orderId, amount: '₹' + (r.amountPaise / 100).toFixed(2), rzp: r.razorpay.razorpayOrderId, payUrl: r.payUrl });
"""),
    req("Checkout again, same Idempotency-Key → same order", "POST", "{{baseUrl}}/checkout",
        headers=[{"key": "Idempotency-Key", "value": "{{idempotencyKey}}"}],
        body={"gateway": "RAZORPAY", "address": ADDRESS},
        desc="Simulates a double-click / network retry. The server returns the SAME order and the SAME Razorpay order.",
        tests=STATUS(200) + """
const r = pm.response.json();
pm.test('Same order id (no duplicate order)', () => pm.expect(r.orderId).to.eql(Number(pm.environment.get('orderId'))));
pm.test('Same Razorpay order (no duplicate charge possible)', () =>
    pm.expect(r.razorpay ? r.razorpay.razorpayOrderId : null).to.eql(pm.environment.get('razorpayOrderId')));
pm.test('Marked as replay', () => pm.expect(r.replayed).to.be.true);
"""),
    req("Pay / retry order → reuses the open Razorpay order", "POST", "{{baseUrl}}/orders/{{orderId}}/pay",
        body={"gateway": "RAZORPAY"}, tests=STATUS(200, 409) + """
if (pm.response.code === 200) {
    pm.test('Still the same Razorpay order', () =>
        pm.expect(pm.response.json().razorpay.razorpayOrderId).to.eql(pm.environment.get('razorpayOrderId')));
} else {
    console.log('Order is already paid/cancelled:', pm.response.json().detail);
}
"""),
    req("Forged payment signature → 400", "POST", "{{baseUrl}}/payments/razorpay/verify",
        body={"orderId": "{{orderId}}", "razorpayOrderId": "{{razorpayOrderId}}",
              "razorpayPaymentId": "pay_FORGED000000", "razorpaySignature": "0000forged"},
        tests=STATUS(400) + """
pm.test('Order was NOT marked paid', () => pm.expect(pm.response.json().detail).to.match(/signature/i));
"""),
    req("Order status (poll after paying)", "GET", "{{baseUrl}}/orders/{{orderId}}", tests=STATUS(200) + """
const o = pm.response.json();
console.log('Order', o.id, o.status, 'payment', o.latestPayment);
pm.test('Order is pending or paid', () => pm.expect(o.status).to.be.oneOf(['PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED']));
"""),
], pre=auto_login("accessToken", "userEmail", "userPassword"),
    desc="1) Checkout creates a real Razorpay order.  2) Open `payUrl` in the browser (signed in as the same "
         "shopper) and pay with Razorpay Checkout.  3) The backend verifies the signature, fetches the payment "
         "from Razorpay, captures it and pushes PAID live.  Nothing here can mark an order paid without a real "
         "Razorpay payment.")

# ----------------------------------------------------------------------------- 05 razorpay direct

razorpay = folder("05 Razorpay API (your account)", [
    req("Razorpay: fetch order", "GET", "{{razorpayApiUrl}}/orders/{{razorpayOrderId}}", auth=RZP_BASIC,
        tests=STATUS(200) + """
const o = pm.response.json();
console.log('Razorpay order', o);
pm.test('Amount matches our order', () => pm.expect(o.amount).to.eql(Number(pm.environment.get('amountPaise'))));
pm.test('Receipt links to our order', () => pm.expect(o.receipt).to.include('sc_order_' + pm.environment.get('orderId')));
pm.test('Razorpay order status', () => pm.expect(o.status).to.be.oneOf(['created', 'attempted', 'paid']));
"""),
    req("Razorpay: payments of the order", "GET", "{{razorpayApiUrl}}/orders/{{razorpayOrderId}}/payments",
        auth=RZP_BASIC, tests=STATUS(200) + """
const items = pm.response.json().items || [];
console.log('Attempts', items.map(p => ({ id: p.id, status: p.status, method: p.method, amount: p.amount })));
const paid = items.find(p => p.status === 'captured' || p.status === 'authorized');
if (paid) {
    pm.environment.set('razorpayPaymentId', paid.id);
    pm.test('Payment found: ' + paid.id + ' (' + paid.status + ')', () => pm.expect(paid.id).to.match(/^pay_/));
    pm.test('At most one successful payment on this Razorpay order', () =>
        pm.expect(items.filter(p => p.status === 'captured').length).to.be.at.most(1));
} else {
    console.log('No successful payment yet - open {{payUrl}} and pay, then re-run this folder.');
}
"""),
    req("Razorpay: fetch payment", "GET", "{{razorpayApiUrl}}/payments/{{razorpayPaymentId}}",
        auth=RZP_BASIC, tests=STATUS(200, 400) + """
if (pm.response.code === 200) {
    const p = pm.response.json();
    pm.test('Payment belongs to our Razorpay order', () => pm.expect(p.order_id).to.eql(pm.environment.get('razorpayOrderId')));
    pm.test('Captured by the backend (auto-capture)', () => pm.expect(p.status).to.be.oneOf(['captured', 'refunded']));
}
"""),
    req("Backend: verify payment (signature from key secret)", "POST", "{{baseUrl}}/payments/razorpay/verify",
        auth=bearer("accessToken"),
        body={"orderId": "{{orderId}}", "razorpayOrderId": "{{razorpayOrderId}}",
              "razorpayPaymentId": "{{razorpayPaymentId}}", "razorpaySignature": "{{razorpaySignature}}"},
        desc="Same call the browser makes after Razorpay Checkout. The pre-request script computes the "
             "checkout signature from your key secret (kept locally in the environment). The backend still "
             "fetches the payment from Razorpay, so this only succeeds for a real payment. Idempotent.",
        pre="""
const cryptoJs = require('crypto-js');
const secret = pm.environment.get('razorpayKeySecret');
const payId = pm.environment.get('razorpayPaymentId');
if (secret && payId) {
    pm.environment.set('razorpaySignature',
        cryptoJs.HmacSHA256(pm.environment.get('razorpayOrderId') + '|' + payId, secret).toString(cryptoJs.enc.Hex));
} else {
    console.warn('Set razorpayKeySecret and pay the order first (razorpayPaymentId is filled by the previous request).');
}
""",
        tests=STATUS(200, 400) + """
if (pm.response.code === 200) {
    const r = pm.response.json();
    pm.test('Order is PAID', () => pm.expect(r.orderStatus).to.be.oneOf(['PAID', 'SHIPPED', 'DELIVERED']));
    pm.test('Payment SUCCEEDED (verified with Razorpay)', () => pm.expect(r.paymentStatus).to.eql('SUCCEEDED'));
}
"""),
    req("Order status after payment", "GET", "{{baseUrl}}/orders/{{orderId}}", auth=bearer("accessToken"),
        tests=STATUS(200) + """
const o = pm.response.json();
pm.test('Order is PAID once Razorpay confirmed', () => pm.expect(o.status).to.be.oneOf(['PAID', 'SHIPPED', 'DELIVERED']));
pm.test('Payment reference is the Razorpay payment id', () =>
    pm.expect(o.latestPayment.gatewayPaymentId).to.eql(pm.environment.get('razorpayPaymentId')));
"""),
], auth=RZP_BASIC, pre=auto_login("accessToken", "userEmail", "userPassword"),
    desc="Talks to the Razorpay API ({{razorpayApiUrl}}) directly with Basic auth (razorpayKeyId / razorpayKeySecret) to "
         "cross-check what the backend recorded. Run it after paying at `payUrl`.")

# ----------------------------------------------------------------------------- 06 webhooks

webhook_body = ('{"entity":"event","event":"payment.captured","payload":{"payment":{"entity":'
                '{"id":"{{razorpayPaymentId}}","order_id":"{{razorpayOrderId}}","amount":{{amountPaise}},"status":"captured"}}}}')

webhooks = folder("06 Webhooks", [
    req("Razorpay webhook with bad signature → 401", "POST", "{{baseUrl}}/webhooks/razorpay", auth=NOAUTH,
        headers=[{"key": "X-Razorpay-Signature", "value": "invalid"},
                 {"key": "X-Razorpay-Event-Id", "value": "evt_postman_bad"}],
        body=webhook_body, tests=STATUS(401)),
    req("Razorpay webhook, correctly signed (re-delivery)", "POST", "{{baseUrl}}/webhooks/razorpay", auth=NOAUTH,
        headers=[{"key": "X-Razorpay-Signature", "value": "{{webhookSignature}}"},
                 {"key": "X-Razorpay-Event-Id", "value": "evt_postman_{{$timestamp}}"}],
        body=webhook_body,
        desc="Signs the body with razorpayWebhookSecret (same as Razorpay does). The backend re-fetches the "
             "payment from Razorpay before acting, so a signed body for a payment that does not exist changes nothing.",
        pre="""
const cryptoJs = require('crypto-js');
const secret = pm.environment.get('razorpayWebhookSecret');
const raw = pm.variables.replaceIn(pm.request.body.raw);
pm.request.body.update(raw); // sign exactly the bytes that are sent
pm.environment.set('webhookSignature', secret ? cryptoJs.HmacSHA256(raw, secret).toString(cryptoJs.enc.Hex) : 'no-secret');
""",
        tests="""
const secret = pm.environment.get('razorpayWebhookSecret');
pm.test(secret ? 'Accepted (200)' : 'No webhook secret set locally -> rejected (401)', () =>
    pm.expect(pm.response.code).to.eql(secret ? 200 : 401));
"""),
])

# ----------------------------------------------------------------------------- 07 orders & reviews

orders = folder("07 Orders & reviews", [
    req("My orders", "GET", "{{baseUrl}}/orders?page=0&size=10", tests=STATUS(200) + """
pm.test('Contains the checkout order', () =>
    pm.expect(pm.response.json().content.map(o => o.id)).to.include(Number(pm.environment.get('orderId'))));
"""),
    req("Order details", "GET", "{{baseUrl}}/orders/{{orderId}}", tests=STATUS(200)),
    req("Write a review", "POST", "{{baseUrl}}/reviews",
        body={"productId": "{{productId}}", "rating": 5, "title": "Great value", "comment": "Tested from Postman"},
        tests=STATUS(201, 409) + """
if (pm.response.code === 201) {
    pm.test('Verified purchase flag reflects a paid order', () => pm.expect(pm.response.json()).to.have.property('verifiedPurchase'));
}
"""),
], pre=auto_login("accessToken", "userEmail", "userPassword"),
    desc="Live order status stream (SSE): `curl -N -H \"Authorization: Bearer <token>\" {{baseUrl}}/orders/{{orderId}}/events`")

# ----------------------------------------------------------------------------- 08 admin

admin = folder("08 Admin (ROLE_ADMIN JWT)", [
    req("Login admin", "POST", "{{baseUrl}}/auth/login", auth=NOAUTH,
        body={"email": "{{adminEmail}}", "password": "{{adminPassword}}"},
        tests=SAVE_TOKEN + "\n" + STATUS(200) + """
const jwt = saveToken('adminAccessToken', pm.response.json());
pm.test('JWT carries role ROLE_ADMIN', () => pm.expect(jwt.payload.role).to.eql('ROLE_ADMIN'));
"""),
    req("Shopper token on admin API → 403", "GET", "{{baseUrl}}/admin/orders", auth=bearer("accessToken"),
        tests=STATUS(403)),
    req("All orders", "GET", "{{baseUrl}}/admin/orders?page=0&size=20", tests=STATUS(200)),
    req("Paid orders", "GET", "{{baseUrl}}/admin/orders?status=PAID", tests=STATUS(200)),
    req("Order details (admin)", "GET", "{{baseUrl}}/orders/{{orderId}}", tests=STATUS(200)),
    req("Mark order shipped (only if PAID)", "PUT", "{{baseUrl}}/admin/orders/{{orderId}}/status",
        body={"status": "SHIPPED"}, tests=STATUS(200, 409) + """
if (pm.response.code === 409) console.log('Not paid yet, so it cannot ship:', pm.response.json().detail);
"""),
    req("Create product", "POST", "{{baseUrl}}/products",
        body={"name": "Postman Test Kettle", "description": "Created from Postman", "price": 1299, "mrp": 1799,
              "stock": 25, "imageUrl": "https://images.unsplash.com/photo-1594213114663-d94db9b17125?w=800",
              "attributes": {"capacity": "1.5 L"}},
        tests=STATUS(201) + "pm.environment.set('adminProductId', pm.response.json().id);"),
    req("Restock product (pushes live stock update)", "PUT", "{{baseUrl}}/products/{{adminProductId}}",
        body={"name": "Postman Test Kettle", "description": "Restocked from Postman", "price": 1199, "mrp": 1799,
              "stock": 40, "attributes": {"capacity": "1.5 L"}, "active": True},
        tests=STATUS(200) + "pm.test('Stock updated', () => pm.expect(pm.response.json().stock).to.eql(40));"),
    req("Hide product", "DELETE", "{{baseUrl}}/products/{{adminProductId}}", tests=STATUS(204)),
], auth=bearer("adminAccessToken"), pre=auto_login("adminAccessToken", "adminEmail", "adminPassword"))

# ----------------------------------------------------------------------------- 99 cleanup

cleanup = folder("99 Session end", [
    req("Logout (revokes refresh token)", "POST", "{{baseUrl}}/auth/logout", auth=NOAUTH, tests=STATUS(204)),
    req("Refresh after logout → 401", "POST", "{{baseUrl}}/auth/refresh", auth=NOAUTH, tests=STATUS(401)),
])

collection = {
    "info": {
        "_postman_id": uid("collection"),
        "name": "Smart Cart API",
        "description": (
            "Smart Cart (Spring Boot + PostgreSQL) with real-time Razorpay payments.\n\n"
            "**Setup**: import `SmartCart-Local.postman_environment.json`, select it, and fill "
            "`razorpayKeySecret` (and `razorpayWebhookSecret`) as *current values* - they stay on your machine. "
            "Start the backend with the same keys in `.env`.\n\n"
            "**JWT**: login requests store `accessToken` / `adminAccessToken` and show decoded claims in the "
            "Visualize tab. Folder scripts log in automatically when the token is missing or expiring.\n\n"
            "**Payments**: folder 04 creates a real Razorpay order and prints `payUrl`; pay it in the browser, then run 05."
        ),
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "auth": bearer("accessToken"),
    "item": [setup, auth, catalog, cart, checkout, razorpay, webhooks, orders, admin, cleanup],
}


def var(key, value="", secret=False):
    return {"key": key, "value": value, "type": "secret" if secret else "default", "enabled": True}


environment = {
    "id": uid("env-local"),
    "name": "Smart Cart - Local",
    "values": [
        var("serverUrl", "http://localhost:8080"),
        var("baseUrl", "http://localhost:8080/api"),
        var("frontendUrl", "http://localhost:3000"),
        var("userName", "Postman Shopper"),
        var("userEmail", ""),
        var("userPassword", "Shopper@12345", True),
        var("adminEmail", "admin@smartcart.local"),
        var("adminPassword", "Admin@12345", True),
        var("razorpayApiUrl", "https://api.razorpay.com/v1"),
        var("razorpayKeyId", ""),
        var("razorpayKeySecret", "", True),
        var("razorpayWebhookSecret", "", True),
        var("accessToken", "", True),
        var("accessTokenPayload", ""),
        var("accessTokenExpiresAt", ""),
        var("adminAccessToken", "", True),
        var("adminAccessTokenPayload", ""),
        var("productId", ""),
        var("adminProductId", ""),
        var("idempotencyKey", ""),
        var("orderId", ""),
        var("amountPaise", ""),
        var("razorpayOrderId", ""),
        var("razorpayPaymentId", ""),
        var("razorpaySignature", ""),
        var("payUrl", ""),
        var("webhookSignature", ""),
    ],
    "_postman_variable_scope": "environment",
}

with open(os.path.join(HERE, "SmartCart.postman_collection.json"), "w") as f:
    json.dump(collection, f, indent=2, ensure_ascii=False)
with open(os.path.join(HERE, "SmartCart-Local.postman_environment.json"), "w") as f:
    json.dump(environment, f, indent=2, ensure_ascii=False)
count = sum(len(fo["item"]) for fo in collection["item"])
print(f"Wrote collection with {len(collection['item'])} folders / {count} requests and environment")
