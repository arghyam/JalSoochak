# Global Observability for Microservices

This setup centralizes logs and metrics for all backend microservices using:

- Loki (logs)
- Promtail (log shipping)
- Prometheus (metrics)
- Grafana (visualization)

## Services covered

- `service-discovery` (`8762`)
- `tenant-service` (`8081`)
- `user-service` (`8082`)
- `anomaly-service` (`8083`)
- `telemetry-service` (`8084`)
- `message-service` (`8085`)
- `scheme-service` (`8086`)
- `analytics-service` (`8087`)

## What was standardized

Each service now has:

1. Prometheus registry dependency:
   - `io.micrometer:micrometer-registry-prometheus`
2. Metrics endpoint exposure:
   - `/actuator/prometheus`
3. Consistent file logs:
   - `shared file `backend/logger/logs/backend-services.log``

## Start globally

1. Start all services locally (your usual process).
2. Start observability stack:

```bash
cd backend/logger
docker compose up -d
```

3. Access:
   - Grafana: `http://localhost:3000` (`admin` / `admin`)
   - Prometheus: `http://localhost:9090`
   - Loki API: `http://localhost:3100`

## Verify

- Prometheus:
  - `up{job="tenant-service"}`
  - `up{job="user-service"}`
  - `up{job="analytics-service"}`
- Loki:
  - `{app="tenant-service"}`
  - `{app="message-service"}`
  - `{app="service-discovery"}`

## Dev/Prod direction

For dev/prod, deploy this stack as shared infrastructure and keep app-level config the same.

- In Kubernetes/VMs, prefer host/node-level log agents.
- Keep `/actuator/prometheus` reachable only internally by Prometheus.
- Add environment labels (`dev`, `prod`) from agent/scrape configs.
