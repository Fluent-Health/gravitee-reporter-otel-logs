# Gravitee Reporter OTel Logs

A Gravitee APIM reporter plugin that emits structured per-request log records via the OpenTelemetry Logs SDK, exported via OTLP gRPC to an OpenTelemetry Collector. The collector forwards records to Google Cloud Logging; Grafana Alloy then bridges them to Grafana Cloud Loki for querying. W3C trace context fields (`trace_id`, `span_id`) are first-class citizens in every record, enabling cross-service correlation with Cloud Trace and Sentry.

A project by [Fluent Health](https://github.com/Fluent-Health).

## Quick Links

- [GitHub Releases](https://github.com/Fluent-Health/gravitee-reporter-otel-logs/releases)

## Development

Prerequisites: [`asdf`](https://asdf-vm.com) and Docker.

```bash
asdf plugin add java
asdf plugin add maven
asdf install
mvn verify
```

The `.tool-versions` file pins Java 21 (Temurin) and Maven 3.9. `mvn verify` compiles, runs the unit test suite, and produces the plugin ZIP at `target/gravitee-reporter-otel-logs-*.zip`.

To load the plugin into a local Gravitee gateway, copy the ZIP to the gateway's `plugins-ext/` directory and add the following to `gravitee.yml`:

```yaml
reporters:
  otel-logs:
    enabled: true
    endpoint: "http://localhost:4317"   # OTLP gRPC — point at your OTel Collector
    correlationHeader: "X-Request-ID"
    batchSize: 512
    scheduledDelayMs: 5000
    reportHealthChecks: true
    reportLogs: false
    reportMessageMetrics: true
```

The `endpoint` must resolve to a running OpenTelemetry Collector that accepts OTLP gRPC. Cloud Logging does not expose a native OTLP gRPC endpoint — the collector handles translation. See [GCP Configuration](#gcp-configuration) below.

## Testing

**Unit tests** (no Docker required):

```bash
mvn verify
```

**Integration tests** (Docker required — pulls OTel Collector and Loki images, ~1–2 minutes):

```bash
mvn verify --activate-profiles integration-test
```

Starts an OTel Collector and Loki in containers, emits sample log records for each reportable type, and asserts via the Loki HTTP API that records arrived with the correct attributes and trace IDs.

**GCP live integration test** (requires Application Default Credentials and Cloud Logging API enabled):

```bash
export GOOGLE_CLOUD_PROJECT=your-project-id
mvn verify --activate-profiles gcloud-integration-test
```

Writes a uniquely-identified log record to Cloud Logging via the REST API and polls for up to 90 seconds until it appears, verifying end-to-end credential validity, write, and readback.

## Deployment

Releases follow **semver tagging**. To publish a new release:

1. Create a GitHub Release with a semver tag (e.g. `v1.0.0`).
2. The `release.yml` workflow sets the Maven version, builds the plugin ZIP, and attaches it to the release automatically.
3. Copy the released ZIP into the Gravitee gateway `plugins-ext/` directory and restart the gateway pod. The plugin is picked up on startup.

## Additional Info

### GCP Configuration

Cloud Logging does not expose a native OTLP gRPC endpoint. This plugin sends records to an **OpenTelemetry Collector**, which translates them and writes to Cloud Logging using the native API. On GKE, the collector runs as a DaemonSet or sidecar alongside the Gravitee gateway pods.

#### 1. IAM — grant the collector write access

The Kubernetes service account used by the OTel Collector pod must be bound to a GCP service account with `roles/logging.logWriter`. Using Workload Identity Federation:

```bash
# Create a dedicated GCP service account for the collector
gcloud iam service-accounts create otel-collector \
  --project=YOUR_PROJECT_ID \
  --display-name="OTel Collector"

# Grant Cloud Logging write permission
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:otel-collector@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/logging.logWriter"

# Bind the GCP service account to the Kubernetes service account (Workload Identity)
gcloud iam service-accounts add-iam-policy-binding \
  otel-collector@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="serviceAccount:YOUR_PROJECT_ID.svc.id.goog[YOUR_NAMESPACE/otel-collector]"
```

Then annotate the Kubernetes service account:

```bash
kubectl annotate serviceaccount otel-collector \
  --namespace=YOUR_NAMESPACE \
  iam.gke.io/gcp-service-account=otel-collector@YOUR_PROJECT_ID.iam.gserviceaccount.com
```

#### 2. OTel Collector configuration

Use [`otelcol-contrib`](https://github.com/open-telemetry/opentelemetry-collector-contrib), which includes the `googlecloud` exporter:

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: "0.0.0.0:4317"

processors:
  resource:
    attributes:
      - action: upsert
        key: gcp.project_id
        value: "YOUR_PROJECT_ID"
      - action: upsert
        key: service.name
        value: "gravitee-api-gateway"
      - action: upsert
        key: service.namespace
        value: "gravitee"

exporters:
  googlecloud:
    log:
      default_log_name: "gravitee-api-gateway"

service:
  pipelines:
    logs:
      receivers: [otlp]
      processors: [resource]
      exporters: [googlecloud]
```

The `googlecloud` exporter picks up Application Default Credentials automatically on GKE via the pod's Workload Identity annotation — no credentials file needed.

#### 3. Gravitee gateway configuration

Point the reporter at the collector's in-cluster address:

```yaml
reporters:
  otel-logs:
    enabled: true
    endpoint: "http://otel-collector.observability.svc.cluster.local:4317"
```

Use the `http://` scheme for plain-text in-cluster traffic. Use `https://` only for TLS-terminated external endpoints.

#### 4. Querying logs in Cloud Logging

Logs land under the `gravitee-api-gateway` log name in your project. Open [Cloud Logging](https://console.cloud.google.com/logs) and use these filters:

```
# All gateway logs
logName="projects/YOUR_PROJECT_ID/logs/gravitee-api-gateway"

# Filter by API name
logName="projects/YOUR_PROJECT_ID/logs/gravitee-api-gateway"
jsonPayload.api_name="Appointments API"

# Errors only
logName="projects/YOUR_PROJECT_ID/logs/gravitee-api-gateway"
severity>=ERROR

# Correlate with a specific request by trace ID
trace="projects/YOUR_PROJECT_ID/traces/TRACE_ID_HERE"
```

#### 5. Querying logs in Grafana (via Grafana Alloy → Loki)

Once Grafana Alloy is configured to bridge Cloud Logging to Loki, query with LogQL:

```logql
# All gateway logs
{job="gravitee/gravitee-api-gateway"}

# Errors for a specific API
{job="gravitee/gravitee-api-gateway", severity="ERROR"} | json | api_name=`Appointments API`

# Latency outliers (over 1 second)
{job="gravitee/gravitee-api-gateway"} | json | http_latency_ms > 1000

# Correlate with a Sentry issue by trace ID
{job="gravitee/gravitee-api-gateway"} | json | sentry_trace_id=`TRACE_ID_HERE`
```

### Log Record Schema

Every emitted record carries these OTel SDK fields:

| Field | Source |
|---|---|
| `traceId` | `X-Request-ID` header, or W3C `traceparent` as fallback |
| `spanId` | `traceparent` span component (omitted when absent) |
| `severity` | HTTP status: 5xx → ERROR, 4xx → WARN, else INFO |
| `timestamp` | Event timestamp (nanosecond precision) |
| `body` | `"{METHOD} {path} → {status}"` human-readable summary |

Attributes by event type:

**Metrics (HTTP request):** `api.name`, `api.id`, `api.type`, `http.method`, `http.status`, `http.latency_ms`, `upstream.endpoint`, `context.application`, `context.plan`, `context.subscription`, `gateway.proxy_latency_ms`, `gateway.api_latency_ms`, `error.message`

**EndpointStatus (health checks):** `api.name`, `api.id`, `endpoint.name`, `endpoint.url`, `endpoint.status`, `endpoint.response_time_ms`

**MessageMetrics (async/event-driven APIs):** `api.name`, `api.id`, `message.count`, `message.error_count`, `message.content_length`

**Log (request/response metadata):** `api.name`, `api.id`, `http.method`, `http.status`, `log.request.headers_count`, `log.response.headers_count`, `log.request.content_length`, `log.response.content_length` — disabled by default (`reportLogs: false`)

No payload values are ever logged. No PII or PHI in any attribute.

### Architecture

```
Gravitee Gateway (JVM)
  └── OtelLogsReporter
        └── mapper/* → OtelLogWriter
              └── SdkLoggerProvider
                    └── BatchLogRecordProcessor
                          └── OtlpGrpcLogRecordExporter
                                └── OTel Collector (in-cluster, otelcol-contrib)
                                      └── googlecloud exporter
                                            └── Google Cloud Logging
                                                  └── Grafana Alloy → Grafana Cloud Loki
```

The OTel SDK owns batching and retry. `OtelLogWriter` is a thin wrapper around `SdkLoggerProvider` with no manual queue management.

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md). Report security issues via [GitHub private vulnerability reporting](https://github.com/Fluent-Health/gravitee-reporter-otel-logs/security/advisories/new).

## License

Apache 2.0 — see [LICENSE](./LICENSE).
