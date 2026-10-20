# ADR 0016: Kubernetes with kind and Kustomize

Status: Accepted

## Context

Docker Compose runs the platform on one machine. Most production environments run containers on Kubernetes, which adds
things Compose does not have: health probes that decide whether a pod receives traffic or is restarted, rolling updates,
resource limits, separation of configuration and secrets, and self-healing. The platform should be shown running that way
before it is deployed to any cloud, and it must cost nothing.

## Decision

- **kind** (Kubernetes in Docker) runs a one-node cluster on the developer machine and in CI. It needs only Docker.
- **Kustomize** (built into `kubectl`) organises the manifests: `k8s/base` has no assumption about the cluster, and
  `k8s/overlays/kind` adds the local differences (NodePorts published on 127.0.0.1 through the kind cluster config, and
  the local image tag). A cloud overlay can later change the image names and tags and how traffic enters, without
  touching the base.
- **Probes.** Every application has a startup probe (time for the JVM to boot), a liveness probe (restart when stuck;
  checks nothing outside the process) and a readiness probe (traffic only while the database is reachable). They call the
  Actuator endpoints on the operations port added in phase 4c. During a rolling update, a pod that is shutting down turns
  not-ready first, so it stops receiving requests before it stops.
- **Rolling updates** use `maxUnavailable: 0` and `maxSurge: 1`: a new pod must be ready before an old one is removed.
- **Resources.** Memory requests and limits on every container. No CPU limit on the JVM applications, because throttling
  slows start-up; CPU requests are set so the scheduler can place them.
- **Container hardening.** The applications run as the non-root user from the image, with a read-only root file system
  (only `/tmp` is writable), no privilege escalation, all Linux capabilities dropped, and the default seccomp profile.
- **Configuration and secrets.** Shared settings are in a ConfigMap, passwords in a Secret, both injected as environment
  variables. The database init script and the Keycloak realm are read from the existing files in `docker/` by
  `scripts/kind-up.sh`, so there is one copy of each.
- **Service discovery by name.** The applications reach `postgres`, `kafka`, `keycloak`, `redis`, `jaeger` and each other
  by Service name. `enableServiceLinks: false` is set on every pod: Kubernetes would otherwise inject variables such as
  `KAFKA_PORT` and `KEYCLOAK_PORT` for each Service, and the Kafka and Keycloak images read variables with those prefixes
  as their own settings.
- **Token issuer.** Keycloak keeps its fixed host name `http://localhost:8180`, as in Compose, so tokens carry the same
  issuer whether requested from the host or from a pod, and services fetch keys through `keycloak:8180`.
- **Images** are built locally and loaded into the cluster with `kind load docker-image`, tagged `dev`.
- **CI** has a `kubernetes` job: create a kind cluster, run `scripts/kind-up.sh`, run the same smoke test (with the
  notification logs read through `kubectl`), and print the cluster state when something fails.

## Consequences

- Postgres, Kafka, Keycloak, Redis and Jaeger run in the cluster only for convenience. A real deployment would use managed
  services (for example Azure Database for PostgreSQL), which the Azure phase covers.
- Secrets are in Git as development values. A Kubernetes Secret is only base64-encoded. Real deployments inject secrets
  from a secret manager.
- Kind's default network plugin does not enforce NetworkPolicy, so the operations port is reachable from any pod in the
  cluster. In production a network policy (or a service mesh) should limit who may scrape it and call each service.
- NodePorts are a local convenience. A real cluster would use an ingress controller and TLS in front of the two APIs.
- One replica per application keeps a laptop cluster light. Scaling to two is safe (`kubectl scale`): the outbox relay uses
  row locks that skip claimed rows, and the trade recovery job uses conditional updates. A HorizontalPodAutoscaler needs
  a metrics server, which kind does not install.
- Metrics and dashboards are not deployed to the cluster yet. The pods carry the usual Prometheus scrape annotations,
  and a cluster-wide monitoring stack is a natural next step.
- The CI job is not required for publishing images yet. It can become required once it has proven stable.
