# Gravitee Reporter OTel Logs

A Gravitee APIM reporter plugin that emits structured per-request log records via the OpenTelemetry Logs SDK. Two export modes are supported:

- **`otlp`** (default) — sends records via OTLP gRPC to an OpenTelemetry Collector, which forwards them to Google Cloud Logging. Grafana Alloy can then bridge them to Grafana Cloud Loki for querying.
- **`gcloud`** — writes records directly to Cloud Logging via the REST API v2, without an OTel Collector. Authentication uses Application Default Credentials (ADC) or an optional service-account key file.

W3C trace context fields (`trace_id`, `span_id`) are first-class citizens in every record, enabling cross-service correlation with Cloud Trace and Sentry.

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

To load the plugin into a local Gravitee gateway, copy the ZIP to the gateway's `plugins-ext/` directory and configure it in `gravitee.yml`. See [Configuration](#configuration) below for the full property reference and examples.

## Configuration

Add a `reporters.otel-logs` block to `gravitee.yml`. The `exporter` property selects the mode; all other properties are shared unless noted.

### Property reference

| Property | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `true` | Enables or disables the reporter entirely. |
| `exporter` | string | `otlp` | Export mode: `otlp` (via OTel Collector) or `gcloud` (direct to Cloud Logging REST API). |
| `endpoint` | string | `http://localhost:4317` | OTLP gRPC endpoint. Used only when `exporter: otlp`. |
| `correlationHeader` | string | `X-Request-ID` | Request header used as the trace ID when no W3C `traceparent` is present. |
| `batchSize` | integer | `512` | Maximum number of log records per export batch. |
| `scheduledDelayMs` | integer | `5000` | Milliseconds between batch export attempts. |
| `reportHealthChecks` | boolean | `true` | Emit records for `EndpointStatus` (health check) events. |
| `reportLogs` | boolean | `false` | Emit records for `Log` (full request/response metadata) events. Disable to avoid verbose output. |
| `reportMessageMetrics` | boolean | `true` | Emit records for `MessageMetrics` (async/event-driven API) events. |
| `gcloud.projectId` | string | — | GCP project ID. **Required** when `exporter: gcloud`. |
| `gcloud.logName` | string | `gravitee-api-gateway` | Cloud Logging log name (under `projects/{projectId}/logs/{logName}`). Used only when `exporter: gcloud`. |
| `gcloud.credentialsFile` | string | — | Path to a service-account key JSON file. Omit to use Application Default Credentials (recommended on GKE via Workload Identity). Used only when `exporter: gcloud`. |

### OTLP mode (via OTel Collector)

Routes records through an OpenTelemetry Collector that translates them to Cloud Logging. Use this if you already operate a collector fleet, need multi-destination fanout, or want the Collector to handle credential management.

```yaml
reporters:
  otel-logs:
    enabled: true
    exporter: otlp
    endpoint: "http://otel-collector.YOUR_NAMESPACE.svc.cluster.local:4317"
    correlationHeader: "X-Request-ID"
    batchSize: 512
    scheduledDelayMs: 5000
    reportHealthChecks: true
    reportLogs: false
    reportMessageMetrics: true
```

The `endpoint` must point to an OTel Collector that accepts OTLP gRPC. Use `http://` for plain-text in-cluster traffic. See [OTel Collector configuration](#2-otel-collector-configuration) below for a ready-to-use collector config.

### GCloud mode (direct to Cloud Logging)

Writes directly to Cloud Logging via the REST API. No OTel Collector is needed. Authentication uses Application Default Credentials by default — on GKE this means Workload Identity; no credentials file required.

```yaml
reporters:
  otel-logs:
    enabled: true
    exporter: gcloud
    correlationHeader: "X-Request-ID"
    batchSize: 512
    scheduledDelayMs: 5000
    reportHealthChecks: true
    reportLogs: false
    reportMessageMetrics: true
    gcloud:
      projectId: "your-gcp-project-id"
      logName: "gravitee-api-gateway"
      # credentialsFile: "/path/to/sa-key.json"  # omit on GKE with Workload Identity
```

To use a service-account key file instead of ADC (e.g. on a non-GKE VM):

```yaml
    gcloud:
      projectId: "your-gcp-project-id"
      credentialsFile: "/etc/gravitee/gcp-sa-key.json"
```

See [GCP Configuration](#gcp-configuration) for IAM setup, querying, and the full OTel Collector config for OTLP mode.

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

#### Background

Google does not expose a native OTLP endpoint for logs as of mid-2026. `telemetry.googleapis.com` supports traces only (OTLP logs return `UNIMPLEMENTED`); `logging.googleapis.com` speaks the proprietary `LoggingServiceV2` gRPC API, not OTLP. The `otlp` mode therefore requires an OTel Collector as a translation layer. The `gcloud` mode eliminates that by calling the Cloud Logging REST API directly from the plugin.

When Google adds native OTLP log ingestion to `telemetry.googleapis.com`, switching to collector-free OTLP will be a one-line config change (`endpoint: https://telemetry.googleapis.com`) — no code changes needed.

#### 1. IAM — grant write access

The service account running the pod that hosts the plugin (Gravitee gateway for `gcloud` mode; OTel Collector for `otlp` mode) must have `roles/logging.logWriter`. Using Workload Identity Federation on GKE:

```bash
# Create a GCP service account
gcloud iam service-accounts create gravitee-reporter \
  --project=YOUR_PROJECT_ID \
  --display-name="Gravitee Reporter"

# Grant Cloud Logging write permission
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:gravitee-reporter@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/logging.logWriter"

# Bind to the Kubernetes service account (Workload Identity)
gcloud iam service-accounts add-iam-policy-binding \
  gravitee-reporter@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="serviceAccount:YOUR_PROJECT_ID.svc.id.goog[YOUR_NAMESPACE/gravitee-reporter]"
```

Then annotate the Kubernetes service account:

```bash
kubectl annotate serviceaccount gravitee-reporter \
  --namespace=YOUR_NAMESPACE \
  iam.gke.io/gcp-service-account=gravitee-reporter@YOUR_PROJECT_ID.iam.gserviceaccount.com
```

For the `otlp` mode, apply the same steps to the OTel Collector's service account instead.

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

See the [Configuration](#configuration) section above for the full `gravitee.yml` examples. The minimal additions for GCP:

- **GCloud mode**: set `exporter: gcloud` and `gcloud.projectId: YOUR_PROJECT_ID`. No endpoint needed.
- **OTLP mode**: set `exporter: otlp` and `endpoint: http://otel-collector.YOUR_NAMESPACE.svc.cluster.local:4317`.

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

**OTLP mode** (`exporter: otlp`):

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

**GCloud mode** (`exporter: gcloud`):

```
Gravitee Gateway (JVM)
  └── OtelLogsReporter
        └── mapper/* → OtelLogWriter
              └── SdkLoggerProvider
                    └── BatchLogRecordProcessor
                          └── GclLogRecordExporter (REST API v2, ADC/SA key)
                                └── Google Cloud Logging
                                      └── Grafana Alloy → Grafana Cloud Loki
```

The OTel SDK owns batching and retry in both modes. `OtelLogWriter` is a thin wrapper around `SdkLoggerProvider` with no manual queue management.

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md). Report security issues via [GitHub private vulnerability reporting](https://github.com/Fluent-Health/gravitee-reporter-otel-logs/security/advisories/new).

## License

Apache 2.0 — see [LICENSE](./LICENSE).
