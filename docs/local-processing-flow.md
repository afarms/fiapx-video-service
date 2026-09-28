# Ensaio local de vídeos e processamento

Execute `make integration-flow` no serviço de vídeos com o checkout `fiapx-processing-service` ao lado. Para outro caminho, defina `PROCESSING_PROJECT_DIR`. São necessários JDK 21, Bash, Docker e os PostgreSQL locais dos dois serviços já iniciados, cada um com seu `.env`. O worker usa o banco `fiapx_processing` do seu Compose (ou `DB_NAME` configurado para o ensaio), na porta interna 5432.

O alvo constrói o estágio `media-test` do worker, gera um MP4 sintético de dois segundos com FFmpeg e executa o seguinte percurso:

1. POST multipart real de MP4 válido e conteúdo inválido; S3 é substituído por arquivos locais, preservando os bytes recebidos.
2. Publisher real de vídeos extrai da outbox os envelopes aceitos em PostgreSQL. O transporte simulado os grava sem reconstruir o contrato.
3. Um processo Java separado, no container com FFmpeg 8.1.2, consome os mesmos envelopes com WorkConsumer e usa o PostgreSQL do worker. ZIP e PNGs são reais e verificados; o armazenamento remoto é simulado por arquivos. A mídia inválida resulta em INVALID_MEDIA.
4. ResultPublisher publica os envelopes persistidos; a reentrega do trabalho após publicação produz apenas os mesmos eventos terminais, sem executar mídia novamente.
5. O consumidor de vídeos aplica os terminais antes dos eventos de início, seguido das duplicatas. GET, recibo idempotente, isolamento de proprietário, privacidade dos metadados, prazo de disponibilidade e reinício são verificados.

O teste usa schemas aleatórios exclusivos, removidos no encerramento de cada processo. Containers de aplicação existentes não são substituídos. O container temporário do worker compartilha somente a rede do seu PostgreSQL e recebe apenas credenciais de banco de teste; não recebe perfis nem credenciais AWS. Seu limite é de dois CPUs/1 GiB. Nenhuma fila ou objeto AWS é acessado.

Artefatos sintéticos e `worker.log` ficam em `target/local-flow-*`, fora do Git. Em falha, esse log identifica o estágio do worker. O container tem limite de espera de 180 segundos e é removido ao final; o teste encerra somente o container que criou em caso de timeout. O cache Maven Docker reutiliza o volume `fiapx-processing-media-maven`.

Este ensaio comprova integração do código dos dois serviços com HTTP, PostgreSQL e mídia reais. Não comprova IAM, semântica de S3/SQS, JWT/identidade real, duas instâncias concorrentes, desempenho ou EKS. As suítes separadas continuam necessárias:

```bash
# Em vídeos
make verify
make integration
make integration-flow
# Em processamento
make verify
make integration
make integration-media
```

O fluxo local é opt-in: `make verify` e a CI padrão não iniciam Docker, bancos nem este ensaio. Os testes de cada serviço continuam sem dependência Maven do outro repositório.
