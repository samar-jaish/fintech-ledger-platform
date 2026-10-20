#!/usr/bin/env bash
# Deletes the local kind cluster (and with it every pod and the database). Run: bash scripts/kind-down.sh
set -euo pipefail
kind delete cluster --name "${KIND_CLUSTER:-ledger}"
