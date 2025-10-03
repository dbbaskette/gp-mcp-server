#!/bin/bash

# Load environment variables from .env if it exists
if [ -f .env ]; then
    echo "Loading environment from .env file..."
    set -a  # automatically export all variables
    source .env
    set +a
fi

# --- Configuration ---
APP_NAME="Greenplum MCP Server"
JAR_NAME="gp-mcp-server-0.0.1-SNAPSHOT.jar"
MAVEN_COMMAND="./mvnw"
BUILD_COMMAND="clean compile"
RUN_COMMAND="spring-boot:run -DskipTests"
TARGET_DIR="target"

# --- Colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# --- Functions ---
print_help() {
    echo -e "${BLUE}Usage: ./run.sh [OPTIONS]${NC}"
    echo -e "${BLUE}Options:${NC}"
    echo -e "  ${GREEN}-b, --build${NC}       Build the project before running (compile only, skips tests)"
    echo -e "  ${GREEN}-c, --clean${NC}       Perform a clean build before running (clean, compile, skips tests)"
    echo -e "  ${GREEN}-t, --test${NC}        Run tests before starting"
    echo -e "  ${GREEN}-s, --skip-tests${NC}  Skip tests explicitly (default behavior)"
    echo -e "  ${GREEN}-h, --help${NC}        Display this help message"
    echo ""
    echo -e "${BLUE}Examples:${NC}"
    echo -e "  ./run.sh                # Run without building (skips tests)"
    echo -e "  ./run.sh -b             # Build and run (skips tests)"
    echo -e "  ./run.sh --clean        # Clean build and run (skips tests)"
    echo -e "  ./run.sh -t             # Run tests and start"
    echo -e "  ./run.sh -s             # Explicitly skip tests (same as default)"
    echo ""
    echo -e "${YELLOW}Environment Variables (loaded from .env if present):${NC}"
    echo -e "  ${PURPLE}DB_URL${NC}                    Database connection URL"
    echo -e "  ${PURPLE}DB_USER${NC}                   Database username"
    echo -e "  ${PURPLE}DB_PASSWORD${NC}               Database password"
    echo -e "  ${PURPLE}GP_MCP_ENCRYPTION_KEY${NC}     Encryption key for API key credentials (REQUIRED)"
    echo -e "  ${PURPLE}DB_SEARCH_PATH${NC}            Default search path"
    echo -e "  ${PURPLE}DB_STATEMENT_TIMEOUT_MS${NC}   Query timeout in milliseconds"
    echo -e "  ${PURPLE}OTEL_EXPORTER_OTLP_ENDPOINT${NC} OpenTelemetry endpoint"
    echo ""
    echo -e "${YELLOW}Note: Create a .env file with required variables or set them in your environment.${NC}"
    echo -e "${YELLOW}Generate encryption key with: openssl rand -base64 32${NC}"
}

check_database_config() {
    if [ -z "$GP_MCP_ENCRYPTION_KEY" ]; then
        echo -e "${RED}ERROR: GP_MCP_ENCRYPTION_KEY not set!${NC}"
        echo -e "${YELLOW}This is required for API key credential encryption.${NC}"
        echo -e "${YELLOW}Generate one with: openssl rand -base64 32${NC}"
        echo -e "${YELLOW}Then add to .env file: export GP_MCP_ENCRYPTION_KEY=<your-key>${NC}"
        exit 1
    fi
    if [ -z "$DB_URL" ]; then
        echo -e "${YELLOW}WARNING: DB_URL not set, using default: jdbc:postgresql://localhost:5432/gpdb${NC}"
    fi
    if [ -z "$DB_USER" ]; then
        echo -e "${YELLOW}WARNING: DB_USER not set, using default: gpuser${NC}"
    fi
    if [ -z "$DB_PASSWORD" ]; then
        echo -e "${YELLOW}WARNING: DB_PASSWORD not set, using default: secret${NC}"
    fi
}

display_config() {
    echo -e "${BLUE}--- $APP_NAME Configuration ---${NC}"
    echo -e "${BLUE}Spring Boot Version: $(grep -A 1 '<artifactId>spring-boot-starter-parent</artifactId>' pom.xml | grep '<version>' | sed -E 's/.*<version>(.*)<\/version>.*/\1/') ${NC}"
    echo -e "${BLUE}Spring AI Version: $(grep '<spring-ai.version>' pom.xml | sed -E 's/.*<spring-ai.version>(.*)<\/spring-ai.version>.*/\1/') ${NC}"
    echo -e "${BLUE}Database URL: ${DB_URL:-jdbc:postgresql://localhost:5432/gpdb}${NC}"
    echo -e "${BLUE}Database User: ${DB_USER:-gpuser}${NC}"
    echo -e "${BLUE}Search Path: ${DB_SEARCH_PATH:-public}${NC}"
    echo -e "${BLUE}Statement Timeout: ${DB_STATEMENT_TIMEOUT_MS:-5000}ms${NC}"
    echo -e "${BLUE}OpenTelemetry Endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4317}${NC}"
    echo -e "${BLUE}-----------------------------------${NC}"
}

# --- Main Script ---
BUILD_NEEDED=0
CLEAN_BUILD_NEEDED=0
TEST_NEEDED=0
SKIP_TESTS=1  # Default to skipping tests

# Parse arguments
for arg in "$@"; do
    case $arg in
        -b|--build)
            BUILD_NEEDED=1
            shift
            ;;
        -c|--clean)
            CLEAN_BUILD_NEEDED=1
            BUILD_NEEDED=1 # Clean implies build
            shift
            ;;
        -t|--test)
            TEST_NEEDED=1
            SKIP_TESTS=0    # Enable tests
            BUILD_NEEDED=1  # Test implies build
            shift
            ;;
        -s|--skip-tests)
            SKIP_TESTS=1    # Explicitly skip tests
            shift
            ;;
        -h|--help)
            print_help
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $arg${NC}"
            print_help
            exit 1
            ;;
    esac
done

check_database_config
display_config

# Perform build if requested
if [ $CLEAN_BUILD_NEEDED -eq 1 ]; then
    echo -e "${YELLOW}Performing clean build...${NC}"
    if [ $SKIP_TESTS -eq 1 ]; then
        $MAVEN_COMMAND clean $BUILD_COMMAND -DskipTests || { echo -e "${RED}Clean build failed!${NC}"; exit 1; }
    else
        $MAVEN_COMMAND clean $BUILD_COMMAND || { echo -e "${RED}Clean build failed!${NC}"; exit 1; }
    fi
    echo -e "${GREEN}Clean build successful.${NC}"
elif [ $BUILD_NEEDED -eq 1 ]; then
    echo -e "${YELLOW}Performing build...${NC}"
    if [ $SKIP_TESTS -eq 1 ]; then
        $MAVEN_COMMAND $BUILD_COMMAND -DskipTests || { echo -e "${RED}Build failed!${NC}"; exit 1; }
    else
        $MAVEN_COMMAND $BUILD_COMMAND || { echo -e "${RED}Build failed!${NC}"; exit 1; }
    fi
    echo -e "${GREEN}Build successful.${NC}"
fi

# Run tests if requested
if [ $TEST_NEEDED -eq 1 ]; then
    echo -e "${YELLOW}Running tests...${NC}"
    $MAVEN_COMMAND test || { echo -e "${RED}Tests failed!${NC}"; exit 1; }
    echo -e "${GREEN}Tests passed.${NC}"
fi

# Run the application
echo -e "${BLUE}Starting $APP_NAME...${NC}"
echo -e "${PURPLE}🚀 MCP Server will be available at: http://localhost:8080/mcp${NC}"
echo -e "${PURPLE}📊 Metrics available at: http://localhost:8080/actuator/prometheus${NC}"
echo -e "${PURPLE}🏥 Health check at: http://localhost:8080/actuator/health${NC}"
echo ""
$MAVEN_COMMAND $RUN_COMMAND
