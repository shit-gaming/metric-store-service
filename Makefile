# Metric Store Service - Makefile
# Single command to rule them all!

.PHONY: help start stop restart clean build test logs status dev install-deps

# Default target
help: ## Show this help message
	@echo "Metric Store Service - Available Commands:"
	@echo "=========================================="
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "Quick Start: make start"
	@echo "Run Tests:   make test"

# Main commands
start: build ## 🚀 Start all services (one-click start)
	@echo "🚀 Starting Metric Store Service..."
	@if command -v docker-compose >/dev/null 2>&1; then \
		COMPOSE_CMD="docker-compose"; \
	elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then \
		COMPOSE_CMD="docker compose"; \
	elif command -v podman-compose >/dev/null 2>&1; then \
		COMPOSE_CMD="podman-compose"; \
	else \
		echo "❌ No container runtime found. Please install Docker or Podman."; \
		exit 1; \
	fi; \
	$$COMPOSE_CMD up -d
	@echo "⏳ Waiting for services to be healthy..."
	@sleep 5
	@make status
	@echo ""
	@echo "✅ All services started successfully!"
	@echo "📍 URLs:"
	@echo "   • Application:   http://localhost:8082"
	@echo "   • Swagger UI:    http://localhost:8082/swagger-ui.html"
	@echo "   • MinIO Console: http://localhost:9001 (minioadmin/minioadmin)"

stop: ## 🛑 Stop all services
	@echo "🛑 Stopping all services..."
	@if command -v docker-compose >/dev/null 2>&1; then \
		docker-compose down; \
	elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then \
		docker compose down; \
	elif command -v podman-compose >/dev/null 2>&1; then \
		podman-compose down; \
	fi
	@echo "✅ All services stopped"

restart: stop start ## 🔄 Restart all services
	@echo "✅ Services restarted"

build: ## 📦 Build the application JAR
	@echo "📦 Building application..."
	@if [ ! -f target/metric-store-service-*.jar ]; then \
		echo "Building JAR file..."; \
		./mvnw clean package -DskipTests; \
	else \
		echo "JAR already exists, skipping build"; \
	fi
	@echo "✅ Build complete"

rebuild: clean build ## 🔨 Clean and rebuild everything
	@echo "✅ Rebuild complete"

test: test-unit test-api ## 🧪 Run all tests (unit + API)

test-unit: ## 🧬 Run unit tests
	@echo "🧬 Running unit tests..."
	@./mvnw test
	@echo "✅ Unit tests complete"

test-api: ## 🌐 Run API integration tests
	@echo "🌐 Running API integration tests..."
	@if [ ! -f target/metric-store-service-*.jar ]; then \
		echo "Building application first..."; \
		make build; \
	fi
	@echo "Checking if services are running..."
	@curl -s http://localhost:8082/actuator/health >/dev/null 2>&1 || (echo "Starting services..." && make start)
	@echo "Running comprehensive test suite..."
	@python3 test_comprehensive.py
	@echo "✅ API tests complete"

test-quick: ## ⚡ Run quick smoke tests
	@echo "⚡ Running quick tests..."
	@curl -s http://localhost:8082/actuator/health | jq '.' || echo "Service not running"

logs: ## 📜 Show application logs
	@if command -v docker-compose >/dev/null 2>&1; then \
		docker-compose logs -f metric-store; \
	elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then \
		docker compose logs -f metric-store; \
	elif command -v podman-compose >/dev/null 2>&1; then \
		podman-compose logs -f metric-store; \
	fi

logs-all: ## 📜 Show all service logs
	@if command -v docker-compose >/dev/null 2>&1; then \
		docker-compose logs -f; \
	elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then \
		docker compose logs -f; \
	elif command -v podman-compose >/dev/null 2>&1; then \
		podman-compose logs -f; \
	fi

status: ## 📊 Check service status
	@echo "📊 Service Status:"
	@echo "=================="
	@curl -s http://localhost:8082/actuator/health >/dev/null 2>&1 && echo "✅ Application: Running" || echo "❌ Application: Not running"
	@curl -s http://localhost:9000/minio/health/live >/dev/null 2>&1 && echo "✅ MinIO: Running" || echo "❌ MinIO: Not running"
	@nc -z localhost 5433 2>/dev/null && echo "✅ TimescaleDB: Running" || echo "❌ TimescaleDB: Not running"
	@curl -s http://localhost:3000 >/dev/null 2>&1 && echo "✅ Grafana: Running" || echo "❌ Grafana: Not running"

clean: ## 🧹 Clean build artifacts
	@echo "🧹 Cleaning build artifacts..."
	@./mvnw clean
	@rm -rf target/
	@echo "✅ Clean complete"

clean-all: clean stop ## 🧹 Clean everything (containers + artifacts)
	@echo "🧹 Removing all containers and volumes..."
	@if command -v docker >/dev/null 2>&1; then \
		docker system prune -f --volumes; \
	elif command -v podman >/dev/null 2>&1; then \
		podman system prune -f --volumes; \
	fi
	@echo "✅ Full cleanup complete"

dev: ## 👨‍💻 Start in development mode (hot reload)
	@echo "👨‍💻 Starting in development mode..."
	@if command -v docker-compose >/dev/null 2>&1; then \
		docker-compose -f docker-compose-minimal.yml up -d; \
	elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then \
		docker compose -f docker-compose-minimal.yml up -d; \
	elif command -v podman-compose >/dev/null 2>&1; then \
		podman-compose -f docker-compose-minimal.yml up -d; \
	fi
	@echo "Starting application with hot reload..."
	@./mvnw spring-boot:run

install-deps: ## 📦 Install required dependencies
	@echo "📦 Checking and installing dependencies..."
	@command -v python3 >/dev/null 2>&1 || (echo "❌ Python3 not found. Please install Python 3" && exit 1)
	@command -v jq >/dev/null 2>&1 || (echo "Installing jq..." && brew install jq 2>/dev/null || apt-get install -y jq 2>/dev/null || yum install -y jq 2>/dev/null)
	@pip3 install -r requirements.txt
	@echo "✅ Dependencies installed"

shell-db: ## 🐘 Open PostgreSQL shell
	@if command -v docker >/dev/null 2>&1; then \
		docker exec -it metric-store-timescaledb psql -U postgres -d metrics_db; \
	elif command -v podman >/dev/null 2>&1; then \
		podman exec -it metric-store-timescaledb psql -U postgres -d metrics_db; \
	fi

shell-app: ## 📦 Open application container shell
	@if command -v docker >/dev/null 2>&1; then \
		docker exec -it metric-store-service /bin/sh; \
	elif command -v podman >/dev/null 2>&1; then \
		podman exec -it metric-store-service /bin/sh; \
	fi

# Convenience aliases
up: start ## Alias for 'start'
down: stop ## Alias for 'stop'
ps: status ## Alias for 'status'
