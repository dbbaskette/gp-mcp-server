#!/bin/bash

# Navigate to the MCP server directory
cd /Users/basketted/Projects/gp-mcp-server

echo "Current directory: $(pwd)"
echo "Files in directory:"
ls -la

echo ""
echo "Initializing Git repository..."
git init

echo ""
echo "Adding all files..."
git add .

echo ""
echo "Creating initial commit..."
git commit -m "Initial commit: Greenplum MCP Server implementation

- Complete MCP server with 8 safe query tools
- Security policy enforcement with YAML configuration  
- SQL validation using JSQLParser
- Database configuration with HikariCP
- OpenTelemetry tracing and Prometheus metrics
- Comprehensive documentation and examples
- Unit tests for core functionality
- Cross-platform run scripts"

echo ""
echo "Adding remote origin..."
git remote add origin https://github.com/dbbaskette/gp-mcp-server.git

echo ""
echo "Renaming branch to main..."
git branch -M main

echo ""
echo "Pushing to GitHub..."
git push -u origin main

echo ""
echo "Git operations completed!"
