# Resultados de processamento

O serviço consome `ProcessingStarted`, `ProcessingCompleted` e `ProcessingFailed` v1 da fila de eventos de vídeos. Uma entrega é validada e aplicada em transação curta com lock do vídeo, deduplicação persistida em `video_processing_inbox` e atualização dos metadados. O consumidor usa AWS SDK v2 diretamente e executa `DeleteMessage` somente após confirmação do commit. Falha de banco, commit incerto, mensagem inválida ou conflito não recebem ACK.

## Identidade, ordem e duplicatas

O envelope contém `eventId`, `eventType`, `schemaVersion`, `aggregateId`, `ownerId`, `correlationId`, `occurredAt` e `payload`. O payload identifica `jobId=aggregateId`, `attemptId`, contador de posse `attempt` e `version` positiva. Campos desconhecidos são rejeitados nesta versão. O proprietário deve coincidir com o vídeo aceito, e a correlação deve coincidir com a intenção original persistida na outbox. Vídeo desconhecido ou ainda `UPLOADING` não é criado/aceito a partir de resultado.

Eventos repetidos são comparados por representação canônica dos campos tipados; diferenças apenas na ordem JSON ou representação equivalente de instante não criam conflito. Mesmo `eventId` com conteúdo diferente ou mesma versão com outro evento são conflitos. Após erro, a fila aplica sua política de redrive; o consumidor registra diagnóstico sanitizado e não altera o vídeo.

Um evento mais novo de início atualiza para `PROCESSING`. Sucesso/falha são autossuficientes e podem chegar antes do início: atualizam diretamente de `QUEUED` para `COMPLETED`/`FAILED`. Eventos antigos são registrados como ignorados sem regredir versão ou estado. Um início recebido depois de terminal também é ignorado. Um segundo terminal contraditório é rejeitado; só a duplicata exata do terminal já registrado pode ser reconhecida sem novo efeito.

Sucesso valida bucket configurado, chave `results/{ownerId}/{videoId}/{producerAttemptId}/frames.zip`, tamanho, SHA-256, quantidade de frames e `expiresAt=completedAt+24 horas`. A tentativa produtora da chave pode ser anterior à tentativa que confirmou o resultado, permitindo recuperação de ZIP sem nova extração. O consumidor conserva os instantes do produtor, inclusive quando recebe resultado já expirado. Expiração não muda o status para falha.

## Consultas e upload idempotente

GET `/videos/{id}` e GET `/videos` preservam autenticação, isolamento por dono e paginação. Aos campos existentes são acrescentados, quando presentes:

- `completedAt` e `expiresAt` para sucesso;
- `failedAt` e `failureCode` sanitizado para falha.

Bucket, chaves S3, checksum, tokens de tentativa e conteúdo dos eventos não são expostos. O POST repetido com a mesma chave/conteúdo continua retornando o recibo original `QUEUED`, mesmo depois do processamento. GET/listagem retornam o estado atual. A limpeza de uploads preserva originais aceitos em todos os estados posteriores.

O consumo de resultados não consulta identidade nem exige JWT. A inativação de uma conta não interrompe trabalho previamente aceito; consultas públicas continuam verificando a autorização atual. Download e notificações ainda não estão implementados.

## Operação

Por padrão `PROCESSING_RESULTS_ENABLED=false`. Para ativar, configurar `PROCESSING_RESULTS_ENABLED=true`, `VIDEOS_EVENTS_QUEUE_URL`, `MEDIA_BUCKET_NAME` e `AWS_REGION`; credenciais usam a cadeia padrão do SDK ou `UPLOAD_AWS_PROFILE` local. O consumo funciona com upload desativado. IAM deve permitir ReceiveMessage/DeleteMessage na fila de resultados, sem consumo de DLQ pela aplicação.

Long polling de cinco segundos, uma entrega por vez e visibilidade de 120 segundos; transação SQL limitada a dez segundos. Não há trabalho de mídia ou S3 nessa transação. Duplicatas causadas por falha de exclusão continuam seguras pela inbox. Scheduler com três threads permite consumir resultados sem monopolizar publicação e limpeza de uploads.

Migration 003 amplia os estados e acrescenta versão, metadados públicos, referência privada e inbox. Migrations anteriores são preservadas. `make verify` executa unitários e cobertura; `make integration` verifica PostgreSQL/HTTP reais em schemas isolados, usando identidade e AWS simuladas. Inclui conclusão antes do início, resultado expirado, concorrência, rollback sem ACK, falha de ACK, reinício, isolamento, inativação e POST idempotente após terminal. Validação AWS e reconciliação de mensagens ausentes das filas permanecem pendentes.
