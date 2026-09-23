# Fundação — evidências de 2026-09-22

Escopo: modelo inicial, persistência JDBC, migration preparada, unitários, configuração local e imagem. Não equivale à entrega dos fluxos de upload/identidade/processamento.

| Verificação | Resultado |
| --- | --- |
| Relatórios Surefire do build local (`mvnw.cmd install`, executado pelo responsável) | 40 testes, zero falhas/erros/ignorados: 34 VideoTest + 6 JdbcVideoRepositoryTest |
| JaCoCo CSV/HTML inspecionados | 39/39 linhas e 20/20 branches, 100%; modelo e adaptador JDBC. Launcher excluído |
| Maven Wrapper | 3.9.16 gerado com plugin 3.3.4; dependências Boot 4.1.1/springdoc 3.1.1 resolvidas |
| `docker build -t fiapx-video-service:local .` | Sucesso no Rancher/Moby, JDK compila, `Tests are skipped` com package -DskipTests |
| `docker compose config --quiet` | Sucesso com variável temporária fictícia para validar interpolação; nenhum serviço iniciado |
| Inspeção da imagem | linux/amd64, usuário 10001:10001, Size reportado: 107708838 bytes (~102,7 MiB) |
| Container temporário sem rede | JRE Temurin 21.0.12; javac e mvn ausentes; /app/app.jar presente |

Tamanho é o valor reportado pelo Docker local, não promessa de tamanho de download no registry. Não houve publicação de imagem.

Reprodução dos unitários: `./mvnw -B -ntp verify` no Git Bash; HTML em `target/site/jacoco/index.html`. O gate mínimo é 90% de linhas e branches. Os relatórios completos são gerados localmente/na CI, não versionados.

Não verificado: aplicação inicializando com PostgreSQL real, execução/reexecução das migrations, persistência após reinício, HTTP/OpenAPI em runtime, testes automatizados de integração/contrato/E2E, CI no GitHub e implantação EKS. SQL e isolamento efetivo não são comprovados pelos mocks JDBC. Pipeline preparado com unit-tests → container-build, sem CD.

## Revalidação — 2026-09-23

`make install` executado no Git Bash/Windows com GNU Make: BUILD SUCCESS; 40 testes, zero falhas/erros/ignorados; 39/39 linhas e 20/20 branches cobertos; gate JaCoCo aprovado e artefato instalado no repositório Maven local. O Makefile chama `bash ./mvnw` explicitamente para funcionar quando o GNU Make escolhe CMD como shell das receitas. `mvnw -v` e `mvnw.cmd -v` confirmaram Maven 3.9.16/Java 21.0.2. Ambos os Wrappers são mantidos, para seus ambientes correspondentes.
