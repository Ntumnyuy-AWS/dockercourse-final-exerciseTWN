# Java + MySQL Deployment with Docker, Nexus and DigitalOcean

This repository documents a hands-on DevOps exercise: building a Java application locally, running it with MySQL and phpMyAdmin using Docker Compose, publishing the application image to a private Nexus Docker registry, and deploying the services to an Ubuntu Droplet on DigitalOcean.

> **Project status:** The application image was successfully built, pushed to Nexus and pulled onto the Droplet. MySQL is running and healthy, and phpMyAdmin responds locally on the server. The Java application startup and final browser access checks are still being troubleshot. This README records the work completed so far; it does not claim the deployment is fully finished.

## Architecture

```text
Windows workstation / IntelliJ IDEA
  |  Gradle build + Docker image build
  |  Docker push through SSH tunnel
  v
Nexus Repository Manager (Docker hosted repository :5000)
  |  Docker pull from the Droplet
  v
DigitalOcean Droplet (Ubuntu)
  |
  +-- Docker Compose
       +-- Java application  :8080
       +-- MySQL              :3306 (container/internal database port)
       +-- phpMyAdmin         :8083 -> container port 80
       |
       +-- Named volume: mysql-data
```

Nexus web UI is available on port 8085 in this exercise. The Docker registry connector uses HTTP on port 5000 and is accessed locally on the Droplet at `127.0.0.1:5000`. From Windows, the registry is reached through an SSH tunnel rather than exposing port 5000 publicly.

## Technology stack

- Java 17
- Gradle
- MySQL
- phpMyAdmin
- Docker Engine and Docker Compose
- Sonatype Nexus Repository Manager
- DigitalOcean Ubuntu Droplet
- IntelliJ IDEA on Windows
- SSH

## Repository contents

- `Dockerfile` — packages the built Java JAR into an application image.
- `Docker-compose.yml` — defines the Java, MySQL and phpMyAdmin services and the persistent database volume.
- `.env.example` — template for required environment variables. Copy it to `.env` and provide your own values.
- `README.md` — project notes and deployment steps.

## 1. Build the Java application locally

Open the project in IntelliJ IDEA and build the application with Gradle:

```bash
gradle build
```

The Dockerfile expects the built JAR at:

```text
build/libs/docker-exercises-project-1.0-SNAPSHOT.jar
```

Build the Docker image after the Gradle build has completed. Use a new tag whenever the image contents change, because the Nexus hosted repository in this exercise does not permit redeploying an existing tag.

Example image tag used during deployment:

```text
java-app:1.0-SNAPSHOT-3
```

## 2. Configure the local MySQL and phpMyAdmin services

The Compose file defines three services:

| Service | Purpose | Host port |
|---|---|---:|
| `my-java-app` | Java backend | 8080 |
| `mysql` | Relational database | 3306 (optional host publishing) |
| `phpmyadmin` | Browser-based database administration | 8083 |

The Java application and phpMyAdmin connect to MySQL using the Compose service name `mysql`. Containers on the same Compose network should use the service name and the container port, not `localhost`.

### Persistent database storage

The Compose file declares a named volume:

```yaml
volumes:
  mysql-data:
    driver: local
```

It is mounted at `/var/lib/mysql` in the MySQL container. This keeps database files outside the container's writable layer so they survive normal container recreation.

### Environment variables

Copy the template and fill in your own values:

```bash
cp .env.example .env
```

On Windows, create a local `.env` file using the same variable names. Never commit real passwords or production secrets.

The Compose configuration passes these variables to the services:

- `DB_USER`, `DB_PWD`, `DB_SERVER`, `DB_NAME` — Java application's database connection settings.
- `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` — MySQL initialization settings.
- `PMA_HOST`, `PMA_PORT` — phpMyAdmin's MySQL connection settings.

For this Compose network, `DB_SERVER` and `PMA_HOST` should be `mysql`.

Start the local services:

```bash
docker compose -f Docker-compose.yml up -d
docker compose -f Docker-compose.yml ps
```

Check service logs when troubleshooting:

```bash
docker compose -f Docker-compose.yml logs --tail 100
```

## 3. Prepare the DigitalOcean Droplet

1. Create an Ubuntu Droplet.
2. Configure SSH access and connect to it.
3. Attach a DigitalOcean Cloud Firewall.
4. Install Docker Engine and the Docker Compose plugin.
5. Install and configure Nexus Repository Manager.

Example SSH command (replace the address with your Droplet's IP):

```bash
ssh root@YOUR_DROPLET_IP
```

### Firewall ports used in this exercise

| Port | Service | Access guidance |
|---:|---|---|
| 22 | SSH | Restrict to trusted administrator IPs |
| 8085 | Nexus web interface | Restrict to trusted administrator IPs |
| 5000 | Nexus Docker connector | Keep private; use loopback/SSH tunnel |
| 8080 | Java application | Open only to intended users |
| 8083 | phpMyAdmin | Restrict to trusted administrator IPs |
| 3306 | MySQL | Prefer internal Docker networking; do not expose publicly |

A DigitalOcean Cloud Firewall is separate from Ubuntu's UFW firewall. A service can respond locally on the Droplet while external browser access is blocked by the cloud firewall.

## 4. Configure the Nexus Docker hosted repository

In Nexus Repository Manager:

1. Create a Docker **hosted** repository (used in this exercise: `docker-hosted`).
2. Configure its HTTP connector on port `5000`.
3. Select the configured blob store (used here: `docker-blobs`).
4. Enable the Docker Bearer Token Realm in the security settings.
5. Create a dedicated registry account and grant the required Docker push/pull privileges.

The registry connector in this exercise uses HTTP. On the Droplet, configure Docker to trust this specific local registry.

Example `/etc/docker/daemon.json`:

```json
{
  "insecure-registries": ["127.0.0.1:5000"]
}
```

Docker must load this setting. Verify it with:

```bash
docker info | grep -A5 "Insecure Registries"
```

If you change the daemon configuration, restart Docker during a suitable maintenance window.

### Authenticate on the Droplet

```bash
docker login 127.0.0.1:5000 -u YOUR_NEXUS_DOCKER_USER
```

Enter the password interactively. Do not put it in shell history, source control or this README.

## 5. Push the image from Windows to Nexus through SSH

The Nexus Docker connector is not exposed publicly. A local SSH tunnel forwards a Windows-side address to the Droplet's loopback registry.

The working Windows setup used the WSL/Docker Desktop gateway address `172.30.96.1`. This address is environment-specific and may differ on another machine.

Run the tunnel in a separate Windows CMD terminal and leave that terminal open:

```bat
ssh -N -L 172.30.96.1:5000:127.0.0.1:5000 -o IdentitiesOnly=yes -i "%USERPROFILE%\\.ssh\\id_ed25519" root@YOUR_DROPLET_IP
```

The tunnel command intentionally stays in the foreground and does not show a shell prompt while it is running.

In another Windows terminal, tag the locally built image and push it:

```bash
docker tag java-app:1.0-SNAPSHOT-3 172.30.96.1:5000/java-app:1.0-SNAPSHOT-3
docker push 172.30.96.1:5000/java-app:1.0-SNAPSHOT-3
```

Configure Docker Desktop to allow the same HTTP registry address used by the tunnel (`172.30.96.1:5000`) as an insecure registry. Do not expose Nexus port 5000 to the public internet just to make the push work.

## 6. Copy deployment files and pull the image on Ubuntu

Copy `Docker-compose.yml` and a server-side `.env` file to the Droplet. Keep the real `.env` file private and set restrictive permissions:

```bash
chmod 600 /root/.env
```

The server-side Compose image reference uses the Droplet's local registry endpoint:

```yaml
image: 127.0.0.1:5000/java-app:1.0-SNAPSHOT-3
```

Authenticate and pull the image:

```bash
docker login 127.0.0.1:5000 -u YOUR_NEXUS_DOCKER_USER
docker pull 127.0.0.1:5000/java-app:1.0-SNAPSHOT-3
```

## 7. Deploy with Docker Compose

From the directory containing `Docker-compose.yml` and `.env`:

```bash
docker compose -f Docker-compose.yml up -d
docker compose -f Docker-compose.yml ps
```

To recreate only the Java application after pulling a newer image:

```bash
docker compose -f Docker-compose.yml up -d --no-deps my-java-app
```

Useful diagnostic commands:

```bash
docker compose -f Docker-compose.yml ps
docker compose -f Docker-compose.yml logs --tail 100
docker logs my-java-app --tail 60
docker logs mysql --tail 60
docker logs phpmyadmin --tail 60
```

## 8. Application configuration detail

The Java application's database configuration must read environment variable **names**, not literal values. For example:

```java
String serverName = System.getenv("DB_SERVER");
String user = System.getenv("DB_USER");
String password = System.getenv("DB_PWD");
String dbName = System.getenv("DB_NAME");
```

The Compose service hostname for MySQL is `mysql`, so `DB_SERVER=mysql` is appropriate for container-to-container communication.

The frontend's `index.html` also contained a hardcoded `localhost` backend host. For remote deployment, the frontend must send requests to the correct reachable backend address (or, preferably, a configured public hostname/reverse proxy). After changing frontend or backend code, rebuild the JAR/image, assign a new image tag, push it to Nexus, and pull that tag on the Droplet. Do not reuse an immutable tag if Nexus disallows redeployment.

## 9. Verification

### Nexus registry endpoint

```bash
curl -i http://127.0.0.1:5000/v2/
```

An unauthenticated `401 Unauthorized` response is expected for a protected Docker registry. It confirms that the registry endpoint is reachable; it does not indicate a registry outage.

### phpMyAdmin on the Droplet

```bash
curl -I http://127.0.0.1:8083
```

During this exercise, the server returned `HTTP/1.1 200 OK`, confirming phpMyAdmin was responding locally.

### Java application

After the Java container remains running, test from the Droplet:

```bash
curl -I http://127.0.0.1:8080
```

Then test from a browser using:

```text
http://YOUR_DROPLET_IP:8080
```

For phpMyAdmin, use `http://YOUR_DROPLET_IP:8083` only after restricting the firewall rule to your trusted IP.

## 10. Troubleshooting notes from the exercise

### Docker pull failed with connection refused

Checks performed:

```bash
systemctl status nexus --no-pager
ss -lntp | grep ':5000'
curl -i http://127.0.0.1:5000/v2/
docker info | grep -A5 "Insecure Registries"
docker login 127.0.0.1:5000 -u YOUR_NEXUS_DOCKER_USER
```

Nexus was listening on port 5000 and returned the expected registry `401`. Re-authenticating Docker on the Droplet allowed the image pull to succeed.

### Java application did not remain running

Inspect the actual container logs before rebuilding:

```bash
docker logs my-java-app --tail 60
```

A successful image pull only confirms that the image is available; it does not prove that the application starts or can connect to its database.

### phpMyAdmin was reachable locally but not in the browser

The local `curl` test returned `200 OK`, and UFW reported inactive. The remaining external-access check was the DigitalOcean Cloud Firewall inbound TCP rule for port 8083. Keep this administrative interface restricted to a trusted source IP.

## 11. Security and cleanup recommendations

- Do not commit `.env`, passwords, private SSH keys, registry credentials or production secrets.
- Use `.env.example` as a placeholder template only.
- Restrict Nexus UI and phpMyAdmin to trusted administrator IPs.
- Keep the Nexus Docker connector private and access it through loopback or an SSH tunnel.
- Do not publish MySQL port 3306 to the public internet. The Java and phpMyAdmin containers can use the internal Compose network.
- Use strong, unique credentials; rotate any credentials that were used in a public or shared environment.
- Consider HTTPS and a reverse proxy for public application access.
- Consider a CI/CD workflow to build, tag, push and deploy images consistently.

## 12. Progress checklist

- [x] Create DigitalOcean Ubuntu Droplet.
- [x] Configure a DigitalOcean Cloud Firewall.
- [x] Install/configure Nexus Repository Manager.
- [x] Create a Nexus Docker hosted repository and push/pull permissions.
- [x] Build the Java application locally.
- [x] Run MySQL and phpMyAdmin with Docker Compose locally.
- [x] Configure a persistent MySQL volume.
- [x] Create the Java application Docker image.
- [x] Push the image to Nexus through an SSH tunnel.
- [x] Configure the Droplet Docker daemon for the local HTTP registry.
- [x] Authenticate and pull the image from Nexus on the Droplet.
- [x] Confirm MySQL is healthy on the Droplet.
- [x] Confirm phpMyAdmin responds locally with HTTP 200.
- [ ] Resolve Java application startup on the Droplet.
- [ ] Confirm phpMyAdmin browser access through the DigitalOcean firewall.
- [ ] Confirm Java application browser access.
- [ ] Complete final firewall and secret-handling review.

## What this exercise taught me

This exercise provided practical experience with containerizing a multi-service application, managing persistent data, configuring a private Docker registry, transferring images securely, and troubleshooting deployment issues across local development and cloud infrastructure.

The main lesson: building an image is only one part of deployment. The application, database, environment variables, registry, container network and cloud firewall all need to work together.

---
