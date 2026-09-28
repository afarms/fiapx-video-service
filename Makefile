# Invoke Bash explicitly: Windows GNU Make can use cmd.exe for recipes.
MVNW := bash ./mvnw
ENV_FILE := $(CURDIR)/.env
.DEFAULT_GOAL := install
.PHONY: integration-flow

integration-flow:
	bash scripts/test-processing-flow.sh

.PHONY: install verify integration integration-aws image config-check up down run package

# Build, test and install the artifact in the local Maven repository.
install:
	@echo "Compilando, testando e instalando o artefato local..."
	$(MVNW) install

verify:
	$(MVNW) -B -ntp verify

# Existing .env PostgreSQL, isolated schemas; S3/SQS and identity are mocked.
integration:
	bash scripts/test-upload-postgres.sh

# Explicit opt-in: creates test objects and sends messages to the configured AWS resources.
integration-aws:
	bash scripts/test-upload-aws.sh

image:
	docker build -t fiapx-video-service:local .

config-check:
	docker compose config --quiet

up:
	docker compose up --build -d --wait

down:
	docker compose down

run:
	@bash -ec 'if [ ! -f "$(ENV_FILE)" ]; then \
		echo "Arquivo .env nao localizado: $(ENV_FILE)"; \
		exit 1; \
	fi; \
	set -a; \
	. "$(ENV_FILE)"; \
	set +a; \
	exec $(MVNW) spring-boot:run'

package:
	$(MVNW) clean package
