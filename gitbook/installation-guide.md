# JalSoochak V2 Installation Guide: RKE Cluster & External VM PostgreSQL

This guide provides step-by-step instructions for deploying the JalSoochak services onto a Kubernetes cluster (such as RKE) while hosting the PostgreSQL databases on an external Virtual Machine (VM).

---

## 1. Architecture Overview

In this deployment topology:
*   **Kubernetes (RKE) Cluster**: Runs all core microservices (scheme-service, telemetry-service, user-service, etc.), ingress-nginx, Keycloak (Auth), Kafka Kraft, Redis, MinIO, and the Prometheus-Grafana monitoring stack.
*   **External VM**: Hosts a PostgreSQL engine that serves three independent databases:
    1.  `jalsoochak` (Stores schema and configurations for scheme-service, telemetry-service, tenant-service, user-service, message-service).
    2.  `analytics` (Stores analysis tracking for the analytics service).
    3.  `keycloak` (Stores user identity, roles, and realm data for Keycloak).

---

## 2. Prerequisites

Ensure you have the following command-line tools installed and configured on your deployer machine:
1.  **kubectl**: Logged in and configured with the target RKE cluster context.
2.  **helm** (v3+): Kubernetes package manager.
3.  **helmfile**: Declarative spec for deploying Helm charts.
4.  **sops**: Mozilla SOPS (Secret Operations) for encrypting/decrypting environment secrets.
5.  **age**: Modern file encryption tool used in conjunction with SOPS.

Ensure you also have:
*   Network routing configured so that the RKE nodes can connect to the target PostgreSQL VM port (`5432`).
*   Network routing configured so that incoming HTTP/HTTPS traffic is routed to the RKE Ingress controllers.
*   AWS credentials or an AWS ECR pull token if pulling images from the private registry.

---

## 3. PostgreSQL VM Setup

Perform the following steps directly on your external PostgreSQL host:

### 3.1. Install PostgreSQL
Refer to the official package repository for your OS. For Ubuntu/Debian:
```bash  
sudo apt update
sudo apt install postgresql postgresql-contrib -y
```

### 3.2. Configure Network Listening
By default, PostgreSQL only listens on loopback (`localhost`). You must allow it to listen on the VM's network interface:
1. Open the PostgreSQL config file (typically `/etc/postgresql/<version>/main/postgresql.conf`):
   ```bash
   sudo nano /etc/postgresql/15/main/postgresql.conf
   ```
2. Find the `listen_addresses` line, uncomment it, and set it to listen on all interfaces:
   ```ini
   listen_addresses = '*'
   ```

### 3.3. Configure Client Authentication (Access Rights)
Configure PostgreSQL to allow connections from the Kubernetes cluster's subnet:
1. Open the HBA configuration file (typically `/etc/postgresql/<version>/main/pg_hba.conf`):
   ```bash
   sudo nano /etc/postgresql/15/main/pg_hba.conf
   ```
2. Append a rule letting your Kubernetes cluster nodes and Pod CIDR access the databases (replace `10.0.0.0/16` with your actual Kubernetes node subnet or Pod network range):
   ```text
   # TYPE  DATABASE        USER            ADDRESS                 METHOD
   host    all             all             10.0.0.0/16             scram-sha-256
   ```

3. Restart PostgreSQL to apply the changes:
   ```bash
   sudo systemctl restart postgresql
   ```

### 3.4. Create Databases and Roles
Connect to PostgreSQL as `postgres` admin to provision the required databases and login roles:
```bash
sudo -i -u postgres psql
```

Execute the following SQL commands to initialize the environments:
```sql
-- 1. Create Databases
CREATE DATABASE jalsoochak;
CREATE DATABASE keycloak;
CREATE DATABASE analytics;

-- 2. Create Database Accounts
-- (Replace these passwords with strong, secure credentials)
CREATE USER jalsoochak_user WITH PASSWORD 'JalsoochakSecurePass123';
CREATE USER keycloak_user WITH PASSWORD 'KeycloakSecurePass123';
CREATE USER analytics_user WITH PASSWORD 'AnalyticsSecurePass123';

-- 3. Grant Permissions
GRANT ALL PRIVILEGES ON DATABASE jalsoochak TO jalsoochak_user;
GRANT ALL PRIVILEGES ON DATABASE keycloak TO keycloak_user;
GRANT ALL PRIVILEGES ON DATABASE analytics TO analytics_user;
```

---

## 4. Kubernetes Environment Configurations

All database configurations are supplied to Kubernetes pods using a ConfigMap (`dev-cm.yaml` or target environment configuration) and an encrypted Secret file (`dev-secret.yaml` or target environment secret).

### 4.1. Setup SOPS decryption keys
Ensure your age private key is configured locally so `sops` can decrypt files.
```bash
# Check that age private key is at the default location
cat ~/.config/sops/age/keys.txt

# Or export the SOPS_AGE_KEY environment variable
export SOPS_AGE_KEY="AGE-SECRET-KEY-1..."
```

### 4.2. Update Environment Secrets
To point your application services to the external VM PostgreSQL:
1. Edit the secret file using SOPS interactive editor (which automatically manages encryption/decryption):
   ```bash
   cd infra/deploy-as-code
   sops charts/environments/dev-secret.yaml
   ```
2. Modify the database section elements for both `jalsoochak` and `keycloak` secrets. Refer to the key layouts below:
   
   #### Core Services database configurations (`jalsoochak` Secret Item):
   ```yaml
   host: "<POSTGRES_VM_IP>"
   port: "5432"
   username: "jalsoochak_user"
   password: "JalsoochakSecurePass123"
   database: "jalsoochak"
   db-url: "jdbc:postgresql://<POSTGRES_VM_IP>:5432/jalsoochak?sslmode=disable"
   analytics-db-url: "jdbc:postgresql://<POSTGRES_VM_IP>:5432/analytics?sslmode=disable"
   ```

   #### Keycloak database configurations (`keycloak` Secret Item):
   ```yaml
   host: "<POSTGRES_VM_IP>"
   port: "5432"
   username: "keycloak_user"
   password: "KeycloakSecurePass123"
   database: "keycloak"
   db-url: "jdbc:postgresql://<POSTGRES_VM_IP>:5432/keycloak?sslmode=disable"
   ```

3. Save and close your editor. `sops` will automatically re-encrypt the file.

### 4.3. Create Namespace, ConfigMap, and Secret on Kubernetes
1. Create the target namespace (e.g., `jalsoochak-dev` or as configured):
   ```bash
   kubectl create namespace jalsoochak-dev
   ```
2. Apply the ConfigMaps:
   ```bash
   kubectl apply -f charts/environments/dev-cm.yaml -n jalsoochak-dev
   ```
3. Decrypt and apply the secret file on the fly:
   ```bash
   sops -d charts/environments/dev-secret.yaml | kubectl apply -f - -n jalsoochak-dev
   ```

---

## 5. AWS ECR Credentials Helper (Optional)

If your images are hosted in a private AWS Elastic Container Registry (ECR), you must configure access tokens:
1. Open the cronjob configuration to pull latest credentials:
   ```bash
   nano charts/cronjob/ecr-cred-helper.yaml
   ```
2. Temporarily switch the cron schedule to execute every minute:
   ```yaml
   schedule: "* * * * *"
   ```
3. Apply the helper:
   ```bash
   kubectl apply -f charts/cronjob/ecr-cred-helper.yaml -n jalsoochak-dev
   ```
4. Verify that the `ecr-registry` secret is successfully generated:
   ```bash
   kubectl get secret ecr-registry -n jalsoochak-dev
   ```
5. Once verified, revert the cron schedule inside `ecr-cred-helper.yaml` back to its original rotation configuration (e.g., every 6 hours):
   ```yaml
   schedule: "0 */6 * * *"
   ```
   Apply the reverted changes:
   ```bash
   kubectl apply -f charts/cronjob/ecr-cred-helper.yaml -n jalsoochak-dev
   ```

---

## 6. Services Installation Execution

Navigate to the `infra/deploy-as-code` directory and trigger the deployment.

> [!IMPORTANT]
> Since PostgreSQL is running on an external VM, **do not** apply `postgres-helmfile.yaml`. Applying it would deploy an in-cluster PostgreSQL Server. Only execute `jalsoochak-helmfile.yaml`.

Run Helmfile deploy:
```bash
helmfile apply -f jalsoochak-helmfile.yaml
```

This command automatically:
* Retrieves base credentials from `charts/environments/dev-secret.yaml` dynamically.
* Merges them with domain values inside `charts/environments/jalsoochak-dev.yaml`.
* Packages and installs all listed service releases (Ingress, Keycloak, telemetry-service, flowvision, redis, kafka, etc.) in the correct logical sequence.

---

## 7. Verification Steps

Validate your installation using these commands:

### 7.1. Pod and Ingress status
Verify all pods are in `Running` status and ingress resources have retrieved an IP address.
```bash
# Check pods
kubectl get pods -n jalsoochak-dev

# Check ingresses
kubectl get ingress -n jalsoochak-dev
```

### 7.2. DB Connectivity Verifications
Inspect logs to ensure connection pools are formed:
```bash
# Check connections for Scheme Service
kubectl logs -l app=scheme-service -n jalsoochak-dev --tail=100

# Check connections for Telemetry Service
kubectl logs -l app=telemetry-service -n jalsoochak-dev --tail=100

# Check Keycloak service logs
kubectl logs -l app.kubernetes.io/name=keycloak -n jalsoochak-dev --tail=100
```
Keycloak log output should show database migration runs completing successfully on startup.

---

## 8. Troubleshooting

*   **Database connection timed out**: Ensure VM firewalls (e.g., `ufw` or administrative network groups) permit traffic on port 5432 from all Kubernetes node IPs.
*   **Database authorization failed**: Verify the credentials in `dev-secret.yaml` exactly match the users created inside PostgreSQL. Verify that database parameters in `pg_hba.conf` allow connection methods like `md5` or `scram-sha-256`.
*   **ImagePullBackOff**: Ensure `ecr-registry` secret contains a valid token and the credentials cronjob helper runs successfully.
