#!/bin/bash

# Metric Store Service - Stop Script
# This script gracefully stops the service and infrastructure

echo "🛑 Stopping Metric Store Service"
echo "================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Detect container runtime
CONTAINER_RUNTIME=""
COMPOSE_COMMAND=""

if command -v docker &> /dev/null; then
    CONTAINER_RUNTIME="docker"
    if command -v docker-compose &> /dev/null; then
        COMPOSE_COMMAND="docker-compose"
    elif docker compose version &> /dev/null 2>&1; then
        COMPOSE_COMMAND="docker compose"
    fi
elif command -v podman &> /dev/null; then
    CONTAINER_RUNTIME="podman"
    if command -v podman-compose &> /dev/null; then
        COMPOSE_COMMAND="podman-compose"
    fi
fi

# Check if running in containers or locally
if [ -n "$COMPOSE_COMMAND" ] && ${CONTAINER_RUNTIME} ps | grep -q "metric-store-service"; then
    echo "Detected containerized setup"
    echo "Stopping all services..."
    if ${COMPOSE_COMMAND} down; then
        echo -e "${GREEN}✓${NC} All services stopped"
    else
        echo -e "${RED}✗${NC} Failed to stop services"
    fi
else
    # Stop local Spring Boot application if running
    if pgrep -f "spring-boot:run" > /dev/null; then
        echo "Stopping local application..."
        pkill -f "spring-boot:run"
        echo -e "${GREEN}✓${NC} Application stopped"
    else
        echo -e "${YELLOW}⚠${NC} Application was not running locally"
    fi
    
    # Check for infrastructure containers
    if [ -n "$COMPOSE_COMMAND" ] && ${CONTAINER_RUNTIME} ps | grep -q "metric-store-timescaledb"; then
        echo ""
        read -p "Stop infrastructure services (TimescaleDB, MinIO)? [y/N] " -n 1 -r
        echo ""
        
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            echo "Stopping infrastructure..."
            if ${COMPOSE_COMMAND} -f docker-compose-minimal.yml down; then
                echo -e "${GREEN}✓${NC} Infrastructure stopped"
            else
                echo -e "${RED}✗${NC} Failed to stop infrastructure"
            fi
        fi
    fi
fi
    
# Ask about removing volumes
if [ -n "$COMPOSE_COMMAND" ]; then
    echo ""
    read -p "Remove data volumes? (This will delete all data) [y/N] " -n 1 -r
    echo ""
    
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "Removing volumes..."
        if ${COMPOSE_COMMAND} down -v 2>/dev/null || ${COMPOSE_COMMAND} -f docker-compose-minimal.yml down -v 2>/dev/null; then
            echo -e "${GREEN}✓${NC} Volumes removed"
        else
            echo -e "${YELLOW}⚠${NC} No volumes to remove"
        fi
    fi
fi

echo ""
echo "✅ Shutdown complete"
