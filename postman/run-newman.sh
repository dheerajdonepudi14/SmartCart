#!/usr/bin/env bash
# Runs the whole Postman collection from the command line against a running backend.
# Usage:
#   RAZORPAY_KEY_SECRET=... RAZORPAY_WEBHOOK_SECRET=... ./postman/run-newman.sh
# Pay the order printed in folder 04 (payUrl) in the browser, then run again to see folder 05 pass.
set -euo pipefail
cd "$(dirname "$0")"
npx --yes newman@6 run SmartCart.postman_collection.json \
  -e SmartCart-Local.postman_environment.json \
  --env-var "razorpayKeySecret=${RAZORPAY_KEY_SECRET:-}" \
  --env-var "razorpayWebhookSecret=${RAZORPAY_WEBHOOK_SECRET:-}" \
  --export-environment SmartCart-Local.last-run.postman_environment.json \
  --reporters cli
