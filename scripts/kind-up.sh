#!/usr/bin/env bash
# Builds the four images (three services and the web UI), loads them into a local kind cluster and deploys the platform with the manifests in k8s/.
# Needs: docker, kind, kubectl. Run from the repository root:  bash scripts/kind-up.sh
# Safe to run again: it reuses the cluster and updates what changed.
set -euo pipefail

CLUSTER=${KIND_CLUSTER:-ledger}
NAMESPACE=ledger
MODULES="ledger-service trading-service notification-service"

step() { echo; echo "== $*"; }

step "Cluster"
if kind get clusters | grep -qx "$CLUSTER"; then
  echo "cluster '$CLUSTER' already exists"
else
  kind create cluster --name "$CLUSTER" --config k8s/kind-config.yaml
fi
kubectl config use-context "kind-$CLUSTER" > /dev/null

step "Build the images and load them into the cluster (the cluster cannot see your local Docker images otherwise)"
for m in $MODULES; do
  docker build -f docker/Dockerfile --build-arg MODULE="$m" -t "$m:dev" .
  kind load docker-image "$m:dev" --name "$CLUSTER"
done
docker build -t ledger-ui:dev ui
kind load docker-image ledger-ui:dev --name "$CLUSTER"

step "Namespace and the two files the manifests read: the database init script and the Keycloak realm"
kubectl apply -f k8s/base/namespace.yaml
kubectl -n "$NAMESPACE" create configmap postgres-init --from-file=docker/postgres/init-databases.sql \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" create configmap keycloak-realm --from-file=docker/keycloak/ledger-realm.json \
  --dry-run=client -o yaml | kubectl apply -f -

step "Deploy everything"
kubectl apply -k k8s/overlays/kind
# Pods keep running the image they started with, so a rebuilt image needs a restart to be picked up. Keycloak is restarted
# too: it reads the realm file only when it starts, so a changed realm (for example a new client) is otherwise ignored.
kubectl -n "$NAMESPACE" rollout restart deployment/ledger-service deployment/trading-service deployment/notification-service deployment/ui deployment/keycloak

step "Wait until everything is ready (the first start downloads images and can take several minutes)"
kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout=300s
for d in kafka keycloak redis jaeger; do
  kubectl -n "$NAMESPACE" rollout status "deployment/$d" --timeout=300s
done
for d in ledger-service trading-service notification-service ui; do
  kubectl -n "$NAMESPACE" rollout status "deployment/$d" --timeout=300s
done

step "Done"
kubectl -n "$NAMESPACE" get pods
echo
echo "Ledger API: http://localhost:8080   Trading API: http://localhost:8082   Keycloak: http://localhost:8180   Jaeger: http://localhost:16686   Web UI: http://localhost:8085"
