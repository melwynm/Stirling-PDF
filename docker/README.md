# Docker Setup for Stirling-PDF

This directory contains the organized Docker configurations for the split frontend/backend architecture.

## Directory Structure

```
docker/
├── backend/           # Backend Docker files
│   ├── Dockerfile            # Standard backend
│   ├── Dockerfile.ultra-lite # Minimal backend
│   └── Dockerfile.fat        # Full-featured backend
├── frontend/          # Frontend Docker files
│   ├── Dockerfile     # React/Vite frontend with nginx
│   ├── nginx.conf     # Nginx configuration
│   └── entrypoint.sh  # Dynamic backend URL setup
└── compose/           # Docker Compose files
    ├── docker-compose.yml           # Standard setup
    ├── docker-compose.ultra-lite.yml # Ultra-lite setup
    └── docker-compose.fat.yml       # Full-featured setup
```

## Usage

### Separate Containers (Recommended)

From the project root directory:

```bash
# Standard version
docker-compose -f docker/compose/docker-compose.yml up --build

# Ultra-lite version
docker-compose -f docker/compose/docker-compose.ultra-lite.yml up --build

# Fat version
docker-compose -f docker/compose/docker-compose.fat.yml up --build
```


## Access Points

- **Frontend**: http://localhost:3000
- **Backend API (debugging)**: http://localhost:8080 (TODO: Remove in production)
- **Backend API (via frontend)**: http://localhost:3000/api/*

## Configuration

- **Backend URL**: Set `VITE_API_BASE_URL` environment variable for custom backend locations
- **Custom Ports**: Modify port mappings in docker-compose files
- **Memory Limits**: Adjust memory limits per variant (2G ultra-lite, 4G standard, 6G fat)

## Production Requirements for PDF-to-Image

Use these requirements when publicly exposing `POST /api/v1/convert/pdf/img` and promising support
for PDF uploads up to 20 MB.

- **Upload limit**: Keep the public upload cap close to the promised limit. Use 25 MB for the file
  limit and 30 MB for the request limit to allow multipart overhead.
- **Reverse proxy limit**: Ensure any external proxy, CDN, or load balancer allows at least 30 MB
  request bodies. The bundled nginx configs already allow larger uploads.
- **Container memory**: Run the standard container with at least 4 GB RAM for this workload.
- **JVM heap**: Set the JVM heap below the container limit so native libraries, temp files, and the
  OS have headroom.
- **OOM behavior**: Enable JVM exit on out-of-memory so Docker can restart the service cleanly.
- **Restart policy**: Keep `restart: unless-stopped` enabled.
- **Async processing**: For heavy/public API clients, call
  `/api/v1/convert/pdf/img?async=true` so Stirling can queue resource-heavy conversions.
- **DPI**: Avoid forcing 300 DPI by default. Prefer 150-200 DPI unless the user explicitly needs
  high-resolution output.
- **Monitoring**: Watch `docker stats`, `docker ps -a`, and container logs during launch. Exit code
  137 usually means the container was killed because it ran out of memory.

Recommended compose settings for the first 20 MB PDF-to-image release:

```yaml
services:
  stirling-pdf:
    restart: unless-stopped
    mem_limit: 4g
    environment:
      SYSTEM_MAXFILESIZE: "25"
      SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE: "25MB"
      SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE: "30MB"
      JAVA_CUSTOM_OPTS: "-Xmx3g -XX:+ExitOnOutOfMemoryError"
```

### [Google Drive Integration](https://developers.google.com/workspace/drive/picker/guides/overview)

- **VITE_GOOGLE_DRIVE_CLIENT_ID**: [OAuth 2.0 Client ID](https://console.cloud.google.com/auth/clients/create)
- **VITE_GOOGLE_DRIVE_API_KEY**: [Create New API](https://console.cloud.google.com/apis)
- **VITE_GOOGLE_DRIVE_APP_ID**: This is your [project number](https://console.cloud.google.com/iam-admin/settings) in the GoogleCloud Settings

## Development vs Production

- **Development**: Keep backend port 8080 exposed for debugging
- **Production**: Remove backend port exposure, use only frontend proxy
