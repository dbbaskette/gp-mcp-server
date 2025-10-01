package com.baskettecase.gpmcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Greenplum MCP Server Application
 * 
 * Provides safe query tools for Greenplum/PostgreSQL databases via Model Context Protocol.
 * Supports Streamable-HTTP transport for production deployment.
 * 
 * @see <a href="https://docs.spring.io/spring-ai/reference/1.1-SNAPSHOT/api/mcp/mcp-overview.html">Spring AI MCP Documentation</a>
 */
@Slf4j
@SpringBootApplication
public class GpMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GpMcpServerApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("🚀 Greenplum MCP Server is ready!");
        log.info("📡 MCP Server running on Streamable-HTTP transport");
        log.info("🔧 Available tools: gp.listSchemas, gp.previewQuery, gp.runQuery, gp.explain, gp.openCursor, gp.fetchCursor, gp.closeCursor, gp.cancel");
        log.info("📊 Metrics available at: /actuator/prometheus");
        log.info("🏥 Health check at: /actuator/health");
    }
}
