# Upload e aceite durável

O serviço implementa `POST /videos` autenticado, com uma parte multipart `file` e header `Idempotency-Key` em formato UUID. O dono é obtido do JWT validado e confirmado na identidade antes do processamento multipart. USER e ADMIN enviam somente para si.

## Configuração no host

Preservando as configurações existentes de banco e identidade, configurar no `.env`:

```dotenv
UPLOAD_ENABLED=true
UPLOAD_AWS_PROFILE=fiapx-video-local
AWS_REGION=us-east-1
MEDIA_BUCKET_NAME=fiapx-media-files
PROCESSING_QUEUE_URL=https://sqs.us-east-1.amazonaws.com/ACCOUNT_ID/fiapx-processing-work
UPLOAD_PUBLISHER_ENABLED=false
UPLOAD_CLEANUP_ENABLED=true
```

Copiar a URL real da fila do console SQS somente para o arquivo local. O perfil `fiapx-video-local` em `~/.aws/config` assume a role usando o `source_profile=rafael-admin` já configurado. O SDK inclui STS e renova as credenciais temporárias; não colocar access keys no `.env` nem usar a role do pipeline. `make run` carrega as variáveis e inicia a aplicação no host com o PostgreSQL existente.

`UPLOAD_ENABLED` vem desativado no exemplo. Ativá-lo habilita acesso real ao bucket e, por padrão, reconciliação de originais antigos. `UPLOAD_PUBLISHER_ENABLED` permanece falso enquanto não houver consumidor: eventos ficam duráveis na outbox, pois a retenção SQS é finita. Ativar publicação somente quando o consumidor estiver pronto ou para uma verificação controlada. S3 indisponível antes do aceite gera 503; SQS indisponível após o commit não desfaz um aceite.

No Compose, o volume `upload-temp` armazena o spool multipart e os arquivos temporários fora do heap, preservando o filesystem principal somente leitura. O container não herda os perfis AWS do host; para desenvolvimento com o perfil local, usar `make run`. Credenciais de runtime no EKS serão fornecidas pela identidade do Pod. O build continua multi-stage JDK/JRE, sem FFmpeg e sem testes durante o empacotamento da imagem.

## Requisição e resposta

No Git Bash, com `TOKEN` contendo o JWT e `UPLOAD_KEY` contendo um UUID gerado pelo cliente:

```bash
curl -i http://localhost:8080/videos \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $UPLOAD_KEY" \
  -F 'file=@/c/caminho/video.mp4'
```

O primeiro aceite retorna 202 e `Location: /videos/{id}` no acesso direto. Pela entrada pública, o contexto encaminhado é preservado: `Location: /api/video/videos/{id}`. Corpo:

```json
{"id":"UUID","originalName":"video.mp4","sizeBytes":12345,"status":"QUEUED","createdAt":"2026-09-27T12:00:00Z"}
```

Não retorna URL/chave S3. A resposta confirma original armazenado e metadados/outbox confirmados na mesma transação; não confirma validade de conteúdo, conclusão ou publicação imediata no SQS.

Cada intenção nova usa outra chave. Se a conexão falhar, repetir arquivo/nome e a mesma chave. Após o aceite, isso retorna o mesmo recibo sem outro original/evento lógico. A comparação usa nome exato, tamanho real e SHA-256 calculado no servidor. A associação não expira por idade. Chaves iguais de usuários diferentes não compartilham registros.

| Status | Significado |
| --- | --- |
| 400 | Chave, nome/extensão, arquivo vazio ou multipart inválido; campos extras não são aceitos |
| 401/403 | Autenticação/conta sem autorização |
| 409 | `UPLOAD_CONTENT_CONFLICT` para conteúdo divergente; `UPLOAD_IN_PROGRESS` para tentativa ativa ou perdida |
| 413 | Arquivo acima de 100.000.000 bytes ou envelope acima de 101.000.000 bytes |
| 415 | Conteúdo diferente de multipart/form-data ou multipart enviado para outra rota/método |
| 503 | Identidade, banco, storage ou capacidade local indisponível antes do aceite |

Erros são ProblemDetail com código estável e sem detalhes da AWS/SQL. Retentar `UPLOAD_IN_PROGRESS` com a mesma chave após a tentativa terminar ou seu lease vencer. A API aceita MP4, AVI, MOV, MKV, WMV, FLV e WebM; validação real de formato e duração de até 300 segundos será feita pelo worker.

## Consistência e recuperação

- Reserva `UPLOADING` antes do S3, com índice único por dono/chave. Não há transação SQL aberta durante o upload.
- Cada tentativa usa `originals/{ownerId}/{videoId}/{attemptId}` e PUT condicional para não sobrescrever outro original. Hash é enviado como checksum SHA-256.
- Confirmação compara token e prazo da tentativa no banco. `QUEUED` e um evento `VideoProcessingRequested` são gravados na mesma transação. Uma tentativa antiga não confirma após perder posse.
- Falha de resposta do commit provoca releitura; não se apaga um objeto que pode ter sido aceito. Falhas anteriores deixam uma tentativa persistida para recuperação/limpeza.
- Reconciliação lista `originals/` em páginas de 100 e revisita o prefixo após cada passagem, inclusive escritas tardias. Preserva registros legados, objetos aceitos e tentativas ativas. Lock de linha serializa a decisão de limpeza com o aceite. Uma exclusão falha é tentada novamente em varreduras posteriores.
- Dispatcher reivindica uma publicação por vez, envia fora da transação e marca sucesso usando token/lease. Retentativas preservam eventId e envelope; duplicatas físicas continuam possíveis em SQS Standard. O consumidor deverá deduplicar.

O envelope v1 contém `eventId`, `eventType`, `schemaVersion`, `aggregateId`, `ownerId`, `correlationId`, `occurredAt` e `payload` com bucket, objectKey, sizeBytes, sha256 e originalName. Sem JWT, credenciais ou binário. Eventos publicados permanecem no banco; confirmação SQS não representa processamento concluído.

## Limites operacionais

Por padrão: 2 requisições simultâneas, reserva de disco de 500.000.000 bytes e contabilização adicional do spool/arquivo por requisição concorrente. Capacidade indisponível retorna 503 antes do parse. Temporários próprios usam locks separados para funcionar no Windows e são removidos no encerramento; arquivos abandonados com mais de 15 minutos são recuperados na limpeza. O diretório é configurável por `UPLOAD_TEMP_DIRECTORY`.

Lease de upload: 300 segundos, configurável por `UPLOAD_LEASE_SECONDS` e obrigatoriamente maior que o timeout S3 de 120 segundos. Não é expiração da chave do cliente. Limpeza S3: segurança de 15 minutos, intervalo `UPLOAD_CLEANUP_DELAY_MS` (60 segundos). Publisher: intervalo `UPLOAD_PUBLISHER_DELAY_MS` (5 segundos), até 10 publicações por execução, lease de 60 segundos, timeout SQS de 20 segundos e backoff de 5 a 300 segundos, sem descarte por número de tentativas.

O parser multipart usa um subdiretório exclusivo por instância, protegido por lock de processo. Na inicialização, arquivos de parsers interrompidos são removidos sem tocar em instâncias ativas. Pequenos arquivos de lock vazios são preservados para evitar disputa de posse durante inicializações simultâneas.

## Verificações

`make image` constrói a imagem local. `make config-check` valida a configuração Compose sem exibir valores; exige que as variáveis obrigatórias do `.env`, incluindo `IDENTITY_SERVICE_KEY`, estejam preenchidas.

`make verify` executa unitários e gate de cobertura sem AWS/banco. `make integration` lê a conexão local do `.env`, cria schemas aleatórios `it_upload_*` e remove somente esses schemas ao encerrar. Não cria containers nem altera tabelas da aplicação. O usuário do banco precisa de permissão CREATE no banco. Relatórios em `target/failsafe-reports`; a suíte não roda no build da imagem nem na CI unitária.

A integração usa PostgreSQL e HTTP reais, mas simula identidade/S3/SQS. Verifica migrations, preservação de legados, idempotência, transação/outbox, retomada, restart e locks. Não comprova as permissões e o transporte na AWS; essa validação remota permanece separada. Processamento, resultados, ZIP/download e exclusão distribuída ainda não são implementados por este incremento.

### Ensaio AWS opcional

`UPLOAD_AWS_TEST_APPROVED=true make integration-aws` usa o perfil `fiapx-video-local`, região `us-east-1`, bucket `fiapx-media-files` e fila `fiapx-processing-work` da mesma conta da role. Executar somente em ambiente de desenvolvimento sem consumidor ativo e após autorizar essas gravações. Não faz parte de `make integration`, da CI ou do build Docker.

Cria até três objetos sintéticos menores que 1 MiB sob um owner UUID exclusivo, usa schemas locais isolados e remove os objetos/schema ao encerrar. Identidade é simulada; HTTP, SQL e chamadas S3/SQS são reais. Exercita replay, falha SQL após PUT, retomada após restart, limpeza restrita ao prefixo de teste e preservação do original aceito. O relógio da limpeza é avançado apenas no teste para evitar espera de 15 minutos.

Envia duas mensagens do mesmo evento ao SQS, simulando perda da primeira confirmação para verificar reenvio estável. Não consome nem apaga mensagens; elas permanecem até a retenção de quatro dias. Os arquivos sintéticos não são vídeos válidos para o worker e são removidos ao final; por isso este ensaio não deve ser executado com consumidor ativo. A resposta SendMessage comprova aceite pelo SQS, não processamento.

Referências: [perfis do SDK AWS](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-profiles.html), [upload de streams](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/best-practices-s3-uploads.html).
