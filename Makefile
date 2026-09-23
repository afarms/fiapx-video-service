# Invoke Bash explicitly: Windows GNU Make can use cmd.exe for recipes.
MVNW := bash ./mvnw
.DEFAULT_GOAL := install

# Build, test and install the artifact in the local Maven repository.
install:
	@echo "Compilando, testando e instalando o artefato local..."
	$(MVNW) install

.PHONY: install verify up down

verify:
	$(MVNW) -B -ntp verify

up:
	docker compose up --build -d --wait

down:
	docker compose down
