# FIAP X — Serviço de vídeos

Domínio responsável pela submissão de vídeos, metadados, estado público do processamento e autorização de download. Fundação implementada com Java 21, Spring Boot 4.1.1, Maven Wrapper 3.9.16 e PostgreSQL 17. Inclui modelo inicial, adaptador JDBC, migration Liquibase SQL e testes unitários. APIs de negócio, autenticação, S3 e SQS ainda não estão implementados.

## Build e testes unitários

Pré-requisitos: JDK 21 e acesso ao Maven Central no primeiro build. No Git Bash, a partir da raiz:

```bash
./mvnw -B -ntp verify
# Alternativa Windows: ./mvnw.cmd -B -ntp verify
# Com GNU Make instalado: make verify
```

O Wrapper baixa a versão fixa do Maven, sem depender do Maven global. `make install` preserva a instalação do artefato no repositório Maven local e também executa a fase verify. Não é necessário instalar o artefato para executar o serviço.

Os dois scripts são oficiais e devem ser versionados: `mvnw` para Bash/Linux/macOS (incluindo Git Bash) e `mvnw.cmd` para PowerShell/CMD. Ambos usam `.mvn/wrapper/maven-wrapper.properties`. O Makefile chama `bash ./mvnw` explicitamente porque o GNU Make para Windows pode executar receitas pelo CMD mesmo quando iniciado no Git Bash. Git Bash/Bash deve estar no PATH. `install` compila, testa, verifica cobertura, empacota e instala o JAR no cache Maven local; não é apenas download de dependências.

JUnit e Mockito rodam sem Docker/banco. JaCoCo exige pelo menos 90% de linhas e branches; o launcher Spring é a única classe excluída. Relatório: `target/site/jacoco/index.html`; resultados: `target/surefire-reports/`. Integração com banco, contratos e E2E serão adicionados posteriormente. Mocks não comprovam SQL, migrations nem isolamento real no banco.

## Ambiente local — Git Bash e Rancher Desktop

No Rancher Desktop, selecionar o engine **dockerd (Moby)** e aguardar sua inicialização. Kubernetes local não é necessário para Compose. Confirmar:

```bash
docker version
docker compose version
cp .env.example .env
```

Definir uma senha local em `DB_PASSWORD` no `.env` antes de continuar. Esse arquivo não é versionado. Escolher `POSTGRES_PORT` e `APP_PORT` livres, se os padrões 5432/8080 estiverem ocupados.

```bash
docker compose config --quiet
docker compose up --build -d --wait
docker compose ps
curl --fail http://localhost:8080/actuator/health/readiness
curl --fail http://localhost:8080/v3/api-docs
```

Swagger UI: http://localhost:8080/swagger-ui/index.html. Ainda não há endpoints de negócio no documento. OpenAPI é habilitado pelo Compose e desabilitado por padrão fora dele.

O banco usa `postgres:17.11-alpine3.24`, volume persistente e bind apenas em localhost. Liquibase aplica `001-create-videos.sql` na inicialização da aplicação. A tabela inicial aceita somente `UPLOADING`; registrar metadados não confirma aceite durável do processamento. Novos estados, outbox/inbox e resultados terão novas migrations, sem editar changesets aplicados.

```bash
docker compose logs --tail=100 video
docker compose down
```

`down` preserva o volume. Não usar `down -v` para reiniciar: ele apaga os dados locais. Alterar a senha no `.env` não altera automaticamente a senha de um PostgreSQL já inicializado no volume.

## Imagem e CI

Dockerfile multi-stage: JDK 21 + Wrapper compila com `package -DskipTests`; o estágio final contém JRE 21 e o JAR, sem JDK, Maven ou fontes. Runtime Alpine, usuário 10001 e healthcheck de readiness. Compose aplica filesystem somente leitura e `/tmp` temporário.

```bash
docker build -t fiapx-video-service:local .
```

O build da imagem não executa os testes. No workflow, `unit-tests` executa `verify` e a cobertura; `container-build` só inicia após seu sucesso via `needs`. Ambos devem ser checks obrigatórios da main. O workflow está preparado para PR/main, sem publicação de imagem ou deploy. Execução remota depende da criação do repositório GitHub.

Para EKS, configurar probes HTTP em `/actuator/health/liveness` e `/actuator/health/readiness`; Kubernetes não usa o HEALTHCHECK do Dockerfile. Credenciais serão fornecidas pelo ambiente de implantação. Tamanho final e arquitetura precisam ser verificados no build de destino.

## Responsabilidades

- Registrar vídeo vinculado ao usuário autenticado e validar limites de upload.
- Persistir aceite e intenção de processamento de forma transacional.
- Publicar trabalho e consumir resultados por SQS, com idempotência.
- Listar vídeos do próprio usuário e liberar download durante 24 horas após conclusão.
- Limpar seus registros e arquivos quando o usuário for excluído.

Identidade, extração de imagens e notificações pertencem a serviços independentes. Este serviço não executa FFmpeg nem mantém cadastro de usuários.

## Documentação

- [Domínio e regras](docs/domain/videos.md).
- [Limite arquitetural](docs/architecture/boundary.md).
- [Contratos propostos](contracts/README.md).
- [Evidências](evidence/README.md).

A visão integrada, requisitos do desafio, stack compartilhada e Terraform pertencem ao repositório fiapx-infra. URLs dos repositórios serão publicadas quando existirem; este serviço possui build independente.
