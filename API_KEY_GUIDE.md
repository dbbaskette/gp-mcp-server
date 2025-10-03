# API Key Authentication Guide

## Overview

The Greenplum MCP Server now supports API key-based authentication to secure access to your database resources. API keys follow industry best practices with prefixed tokens similar to Stripe's approach.

## API Key Format

```
gpmcp_{environment}_{32-character-token}
```

Examples:
- `gpmcp_live_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6` (Production key)
- `gpmcp_test_x9y8z7w6v5u4t3s2r1q0p9o8n7m6l5k4` (Testing key)

## Bootstrap Process (First-Time Setup)

**The Problem:** You need an API key to use MCP tools, but `gp.generateApiKey` requires authentication!

**The Solution:** On first startup, the server **automatically generates a bootstrap API key**.

### Getting Your Bootstrap Key

1. **Start the server for the first time:**
   ```bash
   ./run.sh
   ```

2. **Copy the bootstrap key from the console output:**
   ```
   ╔══════════════════════════════════════════════════════════════╗
   ║  🔐 BOOTSTRAP API KEY GENERATED                              ║
   ║  ⚠️  SAVE THIS KEY NOW - IT WILL NOT BE SHOWN AGAIN!        ║
   ║  gpmcp_live_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6                ║
   ╚══════════════════════════════════════════════════════════════╝
   ```

3. **Also saved to:** `BOOTSTRAP_API_KEY.txt` (delete after setup)

### Recommended Workflow

```bash
# 1. Get bootstrap key from logs/file → Configure MCP client
# 2. Generate scoped keys → Update client with new keys
# 3. Revoke bootstrap key → Delete BOOTSTRAP_API_KEY.txt
```

## Generating Additional API Keys

### Method 1: Using MCP Tools (After Bootstrap)

Use the `gp.generateApiKey` tool through your MCP client:

```javascript
{
  "name": "gp.generateApiKey",
  "arguments": {
    "environment": "live",           // Required: "live" or "test"
    "description": "Production API key for analytics dashboard",
    "createdBy": "admin",            // Optional
    "expiresInDays": 365,            // Optional: null = never expires
    "allowedDatabases": "analytics_db,sales_db",  // Optional: null = all databases
    "allowedSchemas": "public,reporting"          // Optional: null = all schemas
  }
}
```

**Response includes the full key (shown only once!):**
```
🔑 **New API Key Generated**

**⚠️ SAVE THIS KEY NOW - IT WILL NOT BE SHOWN AGAIN!**

gpmcp_live_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6

**Key Details:**
- Display ID: gpmcp_live_a1b2c3d4***
- Environment: live
- Description: Production API key for analytics dashboard
- Created By: admin
- Expires: 2026-10-02
- Allowed Databases: analytics_db,sales_db
- Allowed Schemas: public,reporting
```

### Method 2: Direct Database Access

If you have direct database access, you can also generate keys programmatically (requires implementation).

## Using API Keys

### Option 1: Authorization Header (Recommended)

Add the API key to the `Authorization` header with "Bearer" prefix:

```bash
curl -H "Authorization: Bearer gpmcp_live_a1b2c3d4..." \
     http://localhost:8082/mcp
```

### Option 2: Authorization Header (No Bearer)

You can also omit the "Bearer" prefix:

```bash
curl -H "Authorization: gpmcp_live_a1b2c3d4..." \
     http://localhost:8082/mcp
```

### Option 3: X-API-Key Header

Alternative header format:

```bash
curl -H "X-API-Key: gpmcp_live_a1b2c3d4..." \
     http://localhost:8082/mcp
```

## MCP Client Configuration

### Claude Desktop Configuration

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "greenplum": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-everything"],
      "env": {
        "MCP_SERVER_URL": "http://localhost:8082/mcp",
        "AUTHORIZATION": "Bearer gpmcp_live_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
      }
    }
  }
}
```

### Cline/Continue Configuration

```json
{
  "mcpServers": {
    "greenplum": {
      "url": "http://localhost:8082/mcp",
      "headers": {
        "Authorization": "Bearer gpmcp_live_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
      }
    }
  }
}
```

### Multiple Environments

You can configure separate keys for different environments:

```json
{
  "mcpServers": {
    "greenplum-prod": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-everything"],
      "env": {
        "MCP_SERVER_URL": "http://localhost:8082/mcp",
        "AUTHORIZATION": "Bearer gpmcp_live_..."
      },
      "prompt": "Production database. Use databaseName: 'production_db'"
    },
    "greenplum-dev": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-everything"],
      "env": {
        "MCP_SERVER_URL": "http://localhost:8082/mcp",
        "AUTHORIZATION": "Bearer gpmcp_test_..."
      },
      "prompt": "Development database. Use databaseName: 'dev_db'"
    }
  }
}
```

## Managing API Keys

### List All Keys

```javascript
{
  "name": "gp.listApiKeys",
  "arguments": {
    "environment": "live"  // Optional filter
  }
}
```

### Revoke a Key

```javascript
{
  "name": "gp.revokeApiKey",
  "arguments": {
    "keyId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

### Delete a Key Permanently

```javascript
{
  "name": "gp.deleteApiKey",
  "arguments": {
    "keyId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

## Security Features

### 1. **Secure Storage**
- Full keys are never stored in the database
- Only SHA-256 hashes are persisted
- Keys are shown only once during generation

### 2. **Key Prefix Display**
- Keys are displayed as `gpmcp_live_a1b2c3d4***` for identification
- First 8 characters of token visible for reference

### 3. **Scoped Access**
- Restrict keys to specific databases
- Restrict keys to specific schemas
- Combine with existing policy-based access control

### 4. **Expiration**
- Optional expiration dates
- Automatic invalidation of expired keys

### 5. **Audit Trail**
- Track when keys were created
- Track last usage timestamps
- Track who created each key

## Database Schema

The API keys are stored in the `api_keys` table:

```sql
CREATE TABLE api_keys (
    id VARCHAR(36) PRIMARY KEY,
    key_prefix VARCHAR(8) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    environment VARCHAR(10) NOT NULL,
    description TEXT,
    active BOOLEAN DEFAULT true,
    created_by VARCHAR(255),
    allowed_databases TEXT,
    allowed_schemas TEXT,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    expires_at TIMESTAMP,
    UNIQUE(key_hash)
);
```

## Disabling API Key Authentication

For development or testing, you can disable API key authentication:

```bash
export API_KEY_ENABLED=false
```

Or in `application.yml`:

```yaml
gp:
  mcp:
    security:
      api-key-enabled: false
```

## Best Practices

1. **Use Different Keys for Different Environments**
   - `live` keys for production
   - `test` keys for development/testing

2. **Rotate Keys Regularly**
   - Generate new keys periodically
   - Revoke old keys after rotation

3. **Scope Keys Appropriately**
   - Limit database/schema access when possible
   - Use expiration dates for temporary access

4. **Store Keys Securely**
   - Never commit keys to version control
   - Use environment variables or secret managers
   - Treat keys like passwords

5. **Monitor Key Usage**
   - Review `last_used_at` timestamps
   - Revoke unused keys
   - Check audit logs regularly

## Troubleshooting

### "Unauthorized" Error

**Problem:** Request returns 401 Unauthorized

**Solutions:**
1. Verify the API key is included in the request
2. Check the key format: `gpmcp_{env}_{token}`
3. Ensure the key hasn't been revoked
4. Check if the key has expired
5. Verify API key authentication is enabled

### Key Not Working After Generation

**Problem:** Newly generated key returns unauthorized

**Solutions:**
1. Ensure you copied the full key (including `gpmcp_` prefix)
2. Check for extra spaces or line breaks
3. Verify the key is marked as `active` in the database
4. Confirm the `api_keys` table exists

### Database Access Denied

**Problem:** API key authenticates but database access fails

**Solutions:**
1. Check `allowed_databases` constraint on the key
2. Verify `allowed_schemas` permits the schema
3. Review policy.yml for additional restrictions
4. Confirm the database name is correct

## Example Workflow

### Complete Setup Example

```bash
# 1. Start the MCP server
./run.sh

# 2. Generate an API key (through MCP client)
# Call: gp.generateApiKey
# Save the returned key: gpmcp_live_abc123...

# 3. Configure your MCP client
# Add to claude_desktop_config.json:
{
  "mcpServers": {
    "greenplum": {
      "env": {
        "AUTHORIZATION": "Bearer gpmcp_live_abc123..."
      }
    }
  }
}

# 4. Test the connection
# Use any MCP tool - authentication happens automatically

# 5. List existing keys
# Call: gp.listApiKeys

# 6. Revoke old keys as needed
# Call: gp.revokeApiKey with keyId
```

## Support

For issues or questions:
- Check server logs: `logs/gp-mcp-server.log`
- Review this guide
- Check application configuration: `application.yml`
