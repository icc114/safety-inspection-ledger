#!/bin/sh
set -eu

npx wrangler d1 execute safety-inspection-ledger --config wrangler.fnos.jsonc --local --persist-to=/data --file=schema.sql
exec npx wrangler dev --config wrangler.fnos.jsonc --local --ip 0.0.0.0 --port 8787 --persist-to=/data
