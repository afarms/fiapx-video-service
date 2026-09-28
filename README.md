# FIAP X — Serviço de vídeos

Domínio responsável pela submissão de vídeos, metadados, estado público do processamento e autorização de download. Fundação implementada com Java 21, Spring Boot 4.1.1, Maven Wrapper 3.9.16 e PostgreSQL 17. Adota Clean Architecture com core independente de framework, VideoGateway e persistência Spring Data JPA na infraestrutura. Inclui migrations Liquibase e testes unitários. Consultas autenticadas e upload com idempotência, S3 e outbox/SQS estão implementados, com publicação desativada por padrão. O produtor foi validado com PostgreSQL e S3/SQS reais; processamento e download permanecem pendentes. Consulte [upload e recuperação](docs/upload.md).

## Build e testes unitários

Pré-requisitos: JDK 21 e acesso ao Maven Central no primeiro build. No Git Bash, a partir da raiz:

```bash
./mvnw -B -ntp verify
# Com GNU Make instalado: make verify
```

O Wrapper baixa a versão fixa do Maven, sem depender do Maven global. `make install` preserva a instalação do artefato no repositório Maven local e também executa a fase verify. Não é necessário instalar o artefato para executar o serviço.

O projeto mantém somente o script `mvnw`, para Git Bash/Linux/macOS. No Windows, executar pelo Git Bash; não há Wrapper nativo para PowerShell/CMD. A pasta `.mvn/` deve permanecer versionada: `.mvn/wrapper/maven-wrapper.properties` informa qual Maven baixar e executar. Ela não é o cache de dependências, que fica em `~/.m2/`.

O Makefile chama `bash ./mvnw` explicitamente porque o GNU Make para Windows pode executar receitas pelo CMD mesmo quando iniciado no Git Bash. Git Bash/Bash deve estar no PATH. `install` compila, testa, verifica cobertura, empacota e instala o JAR no cache Maven local; não é apenas download de dependências.

JUnit e Mockito rodam sem Docker/banco. JaCoCo exige pelo menos 90% de linhas e branches; o launcher Spring é a única classe excluída. A suíte também compila o core com classpath vazio para impedir dependência de framework ou infraestrutura. Relatório: `target/site/jacoco/index.html`; resultados: `target/surefire-reports/`. make integration executa HTTP/PostgreSQL reais usando a conexão do .env em schemas isolados, com identidade/S3/SQS simulados. Não comprova a integração AWS nem o fluxo E2E de processamento.

Após mover pacotes em um checkout existente, executar `./mvnw clean verify` para eliminar classes compiladas nos caminhos antigos.

## Ambiente local — Git Bash e Rancher Desktop

No Rancher Desktop, selecionar o engine **dockerd (Moby)** e aguardar sua inicialização. Kubernetes local não é necessário para Compose. Confirmar:

```bash
docker version
docker compose version
cp .env.example .env
```

Definir uma senha local em `DB_PASSWORD` no `.env` e configurar a identidade conforme a seção abaixo antes de continuar. Esse arquivo não é versionado. Escolher `POSTGRES_PORT` e `APP_PORT` livres, se os padrões 5432/8080 estiverem ocupados.

```bash
docker compose config --quiet
docker compose up --build -d --wait
docker compose ps
curl --fail http://localhost:8080/actuator/health/readiness
curl --fail http://localhost:8080/v3/api-docs
```

Swagger UI: http://localhost:8080/swagger-ui.html (redireciona para a interface). OpenAPI JSON: http://localhost:8080/v3/api-docs. Os caminhos estão explícitos no application.yml. As consultas de vídeos estão documentadas; usar Authorize com o accessToken da identidade. OpenAPI é habilitado pelo Compose; na execução local pelo Makefile, definir `API_DOCS_ENABLED=true` no `.env`.

Para executar Java no host usando o PostgreSQL já iniciado no Docker:

```bash
make run
```

O alvo run carrega o `.env` com Bash e inicia spring-boot:run; não depende de package prévio. `make package` gera target/app.jar; `make verify` também valida o gate de cobertura. Executar apenas uma instância da aplicação por porta: se o serviço video do Compose estiver em 8080, pará-lo antes de usar make run nessa porta.

`SERVER_PORT` define a porta em que o Java escuta (padrão 8080), tanto no host quanto no container. O healthcheck da imagem acompanha essa variável. No Compose, `APP_PORT` define a porta publicada no host e aponta para `SERVER_PORT` no container. Por exemplo, `APP_PORT=8080` e `SERVER_PORT=9090` mantêm o acesso externo em localhost:8080. Com `docker run`, ajustar também o mapeamento `-p 8080:9090` ao usar `-e SERVER_PORT=9090`; `EXPOSE 8080` apenas documenta o padrão da imagem.

Saúde: `/actuator/health`, `/actuator/health/liveness` e `/actuator/health/readiness`. Readiness inclui o banco; essas rotas são fornecidas pelo Actuator, sem controllers próprios.

O banco usa `postgres:17.11-alpine3.24`, volume persistente e bind apenas em localhost. Liquibase aplica 001 e 002 na inicialização. A migration 002 acrescenta UPLOADING/QUEUED, intenção permanente, tentativas e outbox, preservando registros legados. Registrar metadados não confirma aceite: é necessário original persistido e commit de QUEUED/outbox. Resultados terão novas migrations.

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

O build da imagem não executa os testes. No workflow, `unit-tests` executa `verify` e a cobertura; `container-build` só inicia após seu sucesso via `needs`. Ambos devem ser checks obrigatórios da main. O workflow está preparado para PR/main, sem publicação de imagem ou deploy. O repositório GitHub já possui CI para PR/main.

Para EKS, configurar probes HTTP em `/actuator/health/liveness` e `/actuator/health/readiness`; Kubernetes não usa o HEALTHCHECK do Dockerfile. Credenciais serão fornecidas pelo ambiente de implantação. Tamanho final e arquitetura precisam ser verificados no build de destino.

## Consultas autenticadas implementadas

| Rota | Resposta |
| --- | --- |
| `GET /videos?page=0&size=20` | `items`, `page`, `size`, `totalElements` do usuário autenticado |
| `GET /videos/{id}` | `id`, `originalName`, `sizeBytes`, `status`, `createdAt` de um vídeo próprio |

Enviar `Authorization: Bearer <accessToken>` obtido no login da identidade. USER e ADMIN só consultam seus próprios vídeos; `ownerId` não é aceito como filtro. O DTO público não contém a chave de armazenamento. Sem vídeos cadastrados, a lista retorna vazia; ainda não há endpoint de upload.

O serviço valida RS256, issuer, audience, sub, versão e tempo do JWT usando somente a chave pública. Antes de consultar o banco, verifica a conta e a versão atual em `POST /internal/accounts/validate`, autenticado com `X-Service-Key`. Não há cache positivo nem retry automático. Troca de credenciais e inativação impedem o próximo acesso com o token anterior.

Erros: 400 para UUID/paginação inválidos; 401 para ausência, invalidade ou revogação do token; 403 para bloqueio explícito informado pela identidade; 404 idêntico para vídeo ausente ou alheio; 503 para falha de banco, transporte, credencial do serviço ou resposta inconsistente da identidade. O contrato interno requer `code` nos erros: `UNAUTHORIZED`, `FORBIDDEN` e `SERVICE_UNAUTHORIZED`. Erros internos sem código reconhecido falham com 503. Integrar a versão da identidade com esse contrato antes de atualizar vídeos.

### Configuração da identidade

Iniciar o [serviço de identidade](https://github.com/afarms/fiapx-identity-service) e adicionar ao `.env` local:

- `IDENTITY_SERVICE_KEY`: mesmo segredo configurado na identidade, com pelo menos 32 caracteres.
- `IDENTITY_URL`: URL acessível pelo Java no host; padrão `http://localhost:8081`.
- `IDENTITY_DOCKER_URL`: URL acessível pelo container; padrão `http://host.docker.internal:8081`. Em rede Docker compartilhada, usar o nome do serviço e sua porta interna.
- `JWT_PUBLIC_KEY`: PEM público usado pelo Java no host; padrão `file:.local/keys/identity-public.pem`.
- `IDENTITY_PUBLIC_KEY_PATH`: arquivo público montado pelo Compose; padrão `./.local/keys/identity-public.pem`.

Copiar somente a chave **pública** da identidade para esse caminho. A chave privada permanece na identidade. Arquivos PEM e `.local/` são ignorados pelo Git. A aplicação falha na inicialização se a chave estiver ausente ou inválida. O issuer padrão é `fiapx-identity` e a audience é `fiapx-api`, configuráveis por `JWT_ISSUER` e `JWT_AUDIENCE`.

Timeout de conexão padrão de 2s e leitura de 3s, configuráveis por `IDENTITY_CONNECT_TIMEOUT_MS` e `IDENTITY_READ_TIMEOUT_MS` no ambiente da aplicação. Em implantação, restringir a rede interna e proteger o transporte com TLS. Readiness verifica o banco; indisponibilidade da identidade é tratada nas consultas com 503.

### Casos de uso e persistência

`GetVideoUseCase` consulta por ID e proprietário e retorna metadados/estado. Vídeo inexistente ou de outro usuário gera a mesma `VideoNotFoundException`. `ListVideosUseCase` retorna `VideoPage` com itens imutáveis, página, tamanho e total de vídeos daquele proprietário. A paginação começa em zero, aceita tamanho de 1 a 100 e ordena por `createdAt DESC, id DESC`. Página além do último resultado retorna itens vazios, preservando o total.

Os casos de uso ficam no core sem Spring; `BeanConfig` monta suas dependências. A infraestrutura converte a paginação para Spring Data e filtra por proprietário antes de paginar/contar. Falhas de banco geram `VideoPersistenceException`, sem serem tratadas como ausência. Paginação por offset não garante um snapshot entre chamadas concorrentes.

AuthorizeVideoAccessUseCase e AccountAccessGateway mantêm a decisão de autorização no core. O adapter HTTP, decoder JWT e controllers ficam na infraestrutura, com composição no BeanConfig. O proprietário é derivado do JWT validado e a conta é consultada antes do acesso aos vídeos. A chave do objeto presente no domínio é removida na conversão para o DTO público.

## Responsabilidades

- Registrar vídeo vinculado ao usuário autenticado e validar limites de upload.
- Persistir aceite e intenção de processamento de forma transacional.
- Consumir resultados por SQS, com idempotência; publicação de trabalho já implementada com outbox.
- Listar vídeos do próprio usuário e liberar download durante 24 horas após conclusão.
- Limpar seus registros e arquivos quando o usuário for excluído.

Identidade, extração de imagens e notificações pertencem a serviços independentes. Este serviço não executa FFmpeg nem mantém cadastro de usuários.

## Documentação

- [Domínio e regras](docs/domain/videos.md).
- [Limite arquitetural](docs/architecture/boundary.md).
- [Clean Architecture e persistência](docs/architecture/clean-architecture.md).
- [Contratos propostos](contracts/README.md).

A visão integrada, requisitos do desafio, stack compartilhada e Terraform pertencem ao repositório fiapx-infra. Este serviço possui build independente. O ensaio AWS opcional do upload está documentado no guia de upload.
