# Invoke Bash explicitly: Windows GNU Make can use cmd.exe for recipes.
MVNW := bash ./mvnw
ENV_FILE := $(CURDIR)/.env
.DEFAULT_GOAL := install

.PHONY: install verify up down run package

# Build, test and install the artifact in the local Maven repository.
install:
	@echo "Compilando, testando e instalando o artefato local..."
	$(MVNW) install

verify:
	$(MVNW) -B -ntp verify

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
