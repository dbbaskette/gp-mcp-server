# MCP Client Configuration

## Your Server Transport: Streamable-HTTP ✅

Your server is configured for **Streamable-HTTP** transport in `application.yml`:
```yaml
spring.ai.mcp.server:
  protocol: STREAMABLE
  streamable-http:
    mcp-endpoint: /mcp
```

---

## MCP Inspector Configuration

**API Key Format:** `{id}.{secret}`
**Example:** `gpmcp_live_ABC123.xyz789secretpart`

**Add to MCP Inspector config:**

```json
{
  "mcpServers": {
    "greenplum-local": {
      "url": "http://localhost:8082/mcp",
      "transport": {
        "type": "streamable-http"
      },
      "headers": {
        "Authorization": "Bearer gpmcp_live_ABC123.xyz789secretpart"
      }
    }
  }
}
```

**Or use X-API-Key header:**
```json
{
  "mcpServers": {
    "greenplum-local": {
      "url": "http://localhost:8082/mcp",
      "transport": {
        "type": "streamable-http"
      },
      "headers": {
        "X-API-Key": "gpmcp_live_ABC123.xyz789secretpart"
      }
    }
  }
}
```

---

## Claude Desktop Configuration

**Add to `claude_desktop_config.json`:**

**macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`  
**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

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

---

## Remote Server Configuration

**For remote Greenplum MCP server:**

```json
{
  "mcpServers": {
    "greenplum-prod": {
      "url": "https://your-server.com:8082/mcp",
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

**Important for HTTPS:**
- Ensure valid SSL certificate
- Or use self-signed cert with proper trust configuration

---

## Transport Options Comparison

| Transport | Status | Use Case |
|-----------|--------|----------|
| **Streamable-HTTP** | ✅ Active | HTTP servers (your config) |
| **SSE** (Server-Sent Events) | ⚠️ Available | Alternative HTTP transport |
| **stdio** | ❌ Not configured | Process-based servers only |

### To Switch to SSE:

Edit `application.yml`:
```yaml
spring.ai.mcp.server:
  protocol: SSE  # Change from STREAMABLE
```

Then use in client config:
```json
{
  "transport": {
    "type": "sse"
  }
}
```

### stdio is NOT supported for HTTP servers

Your server runs as an HTTP service, not a spawnable process. Clients should use `streamable-http` or `sse` transport.

---

## Testing Connection

**1. Generate API key:**
```bash
curl -X POST http://localhost:8082/admin/api-keys/generate \
  -H "Content-Type: application/json" \
  -d '{
    "username": "gpadmin",
    "password": "your_gp_password",
    "environment": "live",
    "description": "Test key"
  }'
```

**2. Test with curl (basic connectivity):**
```bash
# Health check
curl http://localhost:8082/actuator/health

# MCP endpoint (requires valid request, but tests auth)
curl -H "Authorization: Bearer gpmcp_live_YOUR_KEY" \
  http://localhost:8082/mcp
```

**3. Connect with MCP Inspector:**
- Add config with your API key
- Should see connection successful
- List available tools (gp.listSchemas, gp.runQuery, etc.)

---

## Troubleshooting

### "Connection refused"
- Check server is running: `curl http://localhost:8082/actuator/health`
- Check logs: `tail -f logs/gp-mcp-server.log`

### "Unauthorized" (401)
- Check API key is correct
- Check header format: `Authorization: Bearer gpmcp_live_...`
- Generate new key if needed

### "Wrong transport type" error
- Make sure client config uses `"type": "streamable-http"`
- NOT `"type": "stdio"` or `"type": "http"` (generic)

### MCP Inspector doesn't connect
- Check URL is exactly: `http://localhost:8082/mcp`
- Check transport type is `streamable-http`
- Check API key is in headers, not in URL
- Check server logs for authentication errors

---

## Example: Full Working Config

```json
{
  "mcpServers": {
    "greenplum-local": {
      "url": "http://localhost:8082/mcp",
      "transport": {
        "type": "streamable-http"
      },
      "headers": {
        "Authorization": "Bearer gpmcp_live_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
      }
    },
    "greenplum-prod": {
      "url": "https://gp-mcp.example.com/mcp",
      "transport": {
        "type": "streamable-http"
      },
      "headers": {
        "Authorization": "Bearer gpmcp_live_x9y8z7w6v5u4t3s2r1q0p9o8n7m6l5k4"
      }
    }
  }
}
```

**This configures two servers:**
- `greenplum-local` → Local dev server
- `greenplum-prod` → Production server

Both use Streamable-HTTP transport with API key authentication.

---

## gp-assistant Integration

**To configure gp-assistant (or other MCP clients) to use this server:**

1. **Generate an API key** for the GP user you want gp-assistant to use:
   ```bash
   curl -X POST http://localhost:8082/admin/api-keys/generate \
     -H "Content-Type: application/json" \
     -d '{
       "username": "gp_assistant_user",
       "password": "your_gp_password",
       "environment": "live",
       "description": "gp-assistant production key"
     }'
   ```

2. **Add to gp-assistant MCP configuration:**
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

**Key Points:**
- Each API key maps to a specific Greenplum database user
- The API key's GP user credentials determine access permissions via Greenplum's native RBAC
- Different clients can use different API keys with different GP users for multi-tenant access
- All clients share the same server connection pool configuration
