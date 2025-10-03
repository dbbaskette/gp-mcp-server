# 🚀 Greenplum MCP Server

<div align="center">

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.0--SNAPSHOT-blue.svg)
![Java](https://img.shields.io/badge/Java-21-orange.svg)
![MCP](https://img.shields.io/badge/MCP-Streamable%20HTTP-purple.svg)

**Secure Multi-Tenant Query Tools for Greenplum/PostgreSQL via Model Context Protocol**

[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Build Status](https://img.shields.io/badge/Build-Passing-green.svg)]()

</div>

## 📋 Table of Contents

- [🎯 Overview](#-overview)
- [✨ Features](#-features)
- [🏗️ Architecture](#️-architecture)
- [🚀 Quick Start](#-quick-start)
- [🔧 Configuration](#-configuration)
- [🛠️ MCP Tools](#️-mcp-tools)
- [🔒 Security](#-security)
- [📊 Monitoring](#-monitoring)
- [📚 Documentation](#-documentation)

## 🎯 Overview

The **Greenplum MCP Server** is a production-ready Spring Boot application that exposes safe query tools for Greenplum and PostgreSQL databases via the Model Context Protocol (MCP). It provides secure, **multi-tenant** access through API key authentication, where each API key maps to a specific Greenplum database user, leveraging Greenplum's native RBAC for permissions.

### 🎪 Key Capabilities

- **🔍 Schema Discovery**: Browse schemas, tables, and columns with JSON table format
- **🔒 Safe Query Execution**: Parameterized SELECT queries with policy enforcement
- **📊 Query Analysis**: EXPLAIN plans and query validation
- **🌊 Streaming Support**: Server-side cursors and streaming results
- **👥 Multi-Tenant**: API key-based isolation using different GP users
- **⚡ Performance**: Optimized for large datasets with per-user connection pooling
- **🛡️ Security**: GP RBAC enforcement, SQL injection prevention, encrypted credentials

## ✨ Features

### 🛠️ MCP Tools

| Tool | Description | Features |
|------|-------------|----------|
| `gp.listSchemas` | List database schemas with tables/columns | JSON table format, pagination |
| `gp.previewQuery` | Validate queries without execution | Plan estimation, validation |
| `gp.runQuery` | Execute parameterized SELECT queries | JSON table format, streaming |
| `gp.explain` | Get detailed query execution plans | JSON format, ANALYZE support |
| `gp.openCursor` | Open server-side cursors | Large result sets |
| `gp.fetchCursor` | Fetch rows from cursors | JSON table format |
| `gp.closeCursor` | Close cursors and free resources | Resource management |
| `gp.cancel` | Cancel running queries | Operation control |

### 🔒 Security Features

- **API Key Authentication**: Secure token-based access with encrypted GP credentials
- **Multi-Tenant Isolation**: Each API key maps to a specific GP user
- **Greenplum RBAC**: Native database permissions determine access
- **Per-User Connection Pools**: Isolated HikariCP pools for each API key
- **Credential Encryption**: AES-256-GCM encryption for stored GP passwords
- **SQL Injection Prevention**: Parameterized queries only, SQL parsing validation
- **Policy-Based Limits**: Row count, byte size, and timeout enforcement
- **Read-Only Access**: Blocks all non-SELECT statements

### 📊 Observability

- **OpenTelemetry Tracing**: Distributed tracing for query operations
- **Prometheus Metrics**: Query performance and resource usage metrics
- **Structured Logging**: JSON audit logs with security events
- **Health Checks**: Database connectivity and service health monitoring

## 🏗️ Architecture

```mermaid
graph TB
    A[MCP Client 1] -->|API Key 1| B[Spring Security]
    C[MCP Client 2] -->|API Key 2| B
    B --> D[API Key Filter]
    D --> E[MCP Tools]
    E --> F[Policy Service]
    E --> G[SQL Validator]
    E --> H[Query Tools]
    H --> I[Connection Manager]
    I --> J[HikariCP Pool 1<br/>GP User 1]
    I --> K[HikariCP Pool 2<br/>GP User 2]
    J --> L[Greenplum/PostgreSQL]
    K --> L

    D --> M[Credential Store<br/>AES-256-GCM]
    F --> N[Policy YAML]
    G --> O[JSQLParser]
    H --> P[OpenTelemetry]

    style A fill:#e1f5fe
    style C fill:#e1f5fe
    style B fill:#f3e5f5
    style L fill:#e8f5e8
    style M fill:#ffebee
```

### 🏛️ Component Architecture

- **Spring Security**: API key authentication with SecurityFilterChain
- **API Key Service**: Manages API keys with encrypted GP credentials per key
- **Connection Manager**: Creates per-API-key HikariCP connection pools
- **MCP Server**: Spring AI MCP annotations for tool registration
- **Policy Service**: YAML-based security policy enforcement
- **SQL Validator**: JSQLParser-based query validation
- **Query Tools**: Safe query execution with JSON table format

## 🚀 Quick Start

### 📋 Prerequisites

- **Java 21+**
- **Maven 3.8+**
- **Greenplum/PostgreSQL** database
- **Encryption key** for credential storage

### 🔧 Environment Setup

```bash
# Clone the repository
git clone https://github.com/dbbaskette/gp-mcp-server.git
cd gp-mcp-server

# Generate encryption key for storing GP credentials
openssl rand -base64 32

# Create .env file
cat > .env <<EOF
# Database connection (shared across all API keys)
DB_URL=jdbc:postgresql://localhost:15432/postgres
DB_USER=gpadmin
DB_PASSWORD=your_admin_password

# Encryption key for API key credential storage (REQUIRED!)
GP_MCP_ENCRYPTION_KEY=your_generated_encryption_key_here

# Server configuration
SERVER_PORT=8082
DB_SEARCH_PATH=public
DB_STATEMENT_TIMEOUT_MS=5000
EOF
```

### 🏃‍♂️ Running the Server

```bash
# Build and run
./run.sh

# Or with Maven directly
./mvnw clean spring-boot:run
```

### 🔑 Generate API Keys

**Format:** API keys follow the Spring AI MCP standard: `{id}.{secret}`
**Example:** `gpmcp_live_ABC123.xyz789secretpart`

**Via Web UI:**
```bash
open http://localhost:8082/admin/api-keys
```

**Via curl:**
```bash
curl -X POST http://localhost:8082/admin/api-keys/generate \
  -H "Content-Type: application/json" \
  -d '{
    "username": "gpadmin",
    "password": "VMware1!",
    "environment": "live",
    "description": "Production API key for gp-assistant"
  }'
```

Response:
```json
{
  "success": true,
  "apiKey": "gpmcp_live_ABC123.xyz789secretpart",
  "displayKey": "gpmcp_live_ABC123.***",
  "message": "API key generated successfully"
}
```

### 🧪 Testing with MCP Inspector

**Create config file** at `mcp-inspector-config.json`:
```json
{
  "mcpServers": {
    "greenplum": {
      "url": "http://localhost:8082/mcp",
      "transport": {
        "type": "streamable-http"
      },
      "headers": {
        "Authorization": "Bearer gpmcp_live_YOUR_API_KEY_HERE"
      }
    }
  }
}
```

See [MCP_CLIENT_CONFIG.md](MCP_CLIENT_CONFIG.md) for detailed client setup.

## 🔧 Configuration

### 📄 Application Configuration (`application.yml`)

```yaml
spring:
  datasource:
    # Shared database connection (host/port)
    url: ${DB_URL:jdbc:postgresql://localhost:15432/postgres}
    username: ${DB_USER:gpadmin}
    password: ${DB_PASSWORD:secret}

  ai:
    mcp:
      server:
        protocol: STREAMABLE  # or SSE
        streamable-http:
          mcp-endpoint: /mcp

gp:
  mcp:
    # Database session settings
    search-path: ${DB_SEARCH_PATH:public}
    statement-timeout-ms: ${DB_STATEMENT_TIMEOUT_MS:5000}

    # Security configuration
    security:
      api-key-enabled: true
      # Generate with: openssl rand -base64 32
      encryption-key: ${GP_MCP_ENCRYPTION_KEY:}

    # Policy configuration
    policy:
      max-rows: 10000
      max-bytes-mb: 100
      allowed-schemas: public,information_schema,pg_catalog
      allowed-tables: public.*
```

### 🔒 Security Policy (`policy.yml`)

```yaml
allowed-schemas:
  - public
  - information_schema
  - pg_catalog

allowed-tables:
  - public.*

# Column-level redaction (optional)
redaction-rules:
  "public.users.email":
    type: MASK
    replacement: "***@***.***"
```

### 🌍 Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `GP_MCP_ENCRYPTION_KEY` | AES-256 key for GP credential encryption | ✅ Yes |
| `DB_URL` | Database connection URL (shared) | ✅ Yes |
| `DB_USER` | Admin database username | ✅ Yes |
| `DB_PASSWORD` | Admin database password | ✅ Yes |
| `SERVER_PORT` | HTTP server port | No (default: 8082) |
| `DB_SEARCH_PATH` | Default search path | No (default: public) |
| `DB_STATEMENT_TIMEOUT_MS` | Query timeout (ms) | No (default: 5000) |

## 🛠️ MCP Tools

All tools now return results in **JSON table format** for better parsing:

### 📊 Schema Discovery

```json
{
  "tool": "gp.listSchemas",
  "parameters": {
    "limit": 50,
    "offset": 0,
    "includeTables": true
  }
}
```

Response:
```json
{
  "columns": ["schema_name", "table_name", "table_type"],
  "rows": [
    ["public", "users", "TABLE"],
    ["public", "orders", "TABLE"]
  ],
  "rowCount": 2
}
```

### ⚡ Query Execution

```json
{
  "tool": "gp.runQuery",
  "parameters": {
    "sqlTemplate": "SELECT name, email FROM users WHERE active = :active",
    "params": {"active": true},
    "maxRows": 1000
  }
}
```

Response:
```json
{
  "columns": ["name", "email"],
  "rows": [
    ["John Doe", "john@example.com"],
    ["Jane Smith", "jane@example.com"]
  ],
  "rowCount": 2
}
```

## 🔒 Security

### 🛡️ Multi-Tenant Architecture

1. **API Key Generation**:
   - Each API key stores encrypted GP username/password
   - Credentials encrypted with AES-256-GCM using `GP_MCP_ENCRYPTION_KEY`

2. **Authentication**:
   - Spring Security validates API key on each request
   - Sets SecurityContext for authorized access

3. **Connection Pooling**:
   - Separate HikariCP pool created per API key
   - Each pool uses the API key's GP user credentials
   - Isolation between different clients/users

4. **Authorization**:
   - Greenplum's native RBAC determines what each user can access
   - No application-level schema/table allowlists needed
   - GP handles all permissions via `has_schema_privilege()`, `has_table_privilege()`, etc.

### 🔐 Security Layers

1. **Network**: HTTPS with valid certificates (production)
2. **Authentication**: API key validation with Spring Security
3. **Credential Storage**: AES-256-GCM encryption
4. **Authorization**: Greenplum RBAC (database-level)
5. **Query Validation**: SQL parsing, parameterization required
6. **Policy Enforcement**: Row limits, timeouts, read-only

### 📋 Security Checklist

- ✅ API key authentication
- ✅ Encrypted credential storage
- ✅ Per-user connection isolation
- ✅ Greenplum RBAC enforcement
- ✅ SQL injection prevention
- ✅ Parameterized queries only
- ✅ Multi-statement blocking
- ✅ Dangerous function blocking
- ✅ Resource limit enforcement
- ✅ Audit logging
- ✅ Read-only enforcement

## 📊 Monitoring

### 📈 Metrics

Access Prometheus metrics at `/actuator/prometheus`:

```bash
# Query execution metrics
gp_mcp_query_executions_total
gp_mcp_query_duration_seconds

# Schema discovery metrics
gp_mcp_schema_queries_total

# Connection pool metrics (per API key)
hikaricp_connections_active{pool="api-key-{id}"}
```

### 🔍 Health Checks

```bash
# Service health
curl http://localhost:8082/actuator/health

# Database connectivity
curl http://localhost:8082/actuator/health/db
```

### 📊 Tracing

OpenTelemetry traces include:
- API key authentication spans
- Query execution spans
- Database connection spans
- Policy validation spans

## 📚 Documentation

### 📖 Additional Resources

- [MCP_CLIENT_CONFIG.md](MCP_CLIENT_CONFIG.md) - Client configuration guide
- [API_KEY_GUIDE.md](API_KEY_GUIDE.md) - API key management
- [Spring AI MCP Documentation](https://docs.spring.io/spring-ai/reference/1.1-SNAPSHOT/api/mcp/mcp-overview.html)
- [Greenplum Documentation](https://greenplum.org/docs/)

### 🏗️ Deployment

**Production Checklist:**

1. Generate strong encryption key: `openssl rand -base64 32`
2. Set `GP_MCP_ENCRYPTION_KEY` environment variable
3. Configure database connection (shared host/port)
4. Enable HTTPS with valid certificates
5. Configure Greenplum users with appropriate RBAC
6. Generate API keys for each client/application
7. Monitor connection pool metrics
8. Set up OpenTelemetry tracing backend
9. Configure log aggregation

### 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

### 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

<div align="center">

**Built with ❤️ using Spring Boot, Spring AI, and Greenplum**

[Report Bug](https://github.com/dbbaskette/gp-mcp-server/issues) · [Request Feature](https://github.com/dbbaskette/gp-mcp-server/issues)

</div>
