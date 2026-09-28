# Contratos de vídeos — atuais e propostos

GET /videos, GET /videos/{id} e POST /videos (quando UPLOAD_ENABLED=true) estão implementados. O contrato de upload e VideoProcessingRequested está em [upload e recuperação](../docs/upload.md). O download completo está implementado quando DOWNLOAD_ENABLED=true; consulte [download e retenção](../docs/download-retention.md). O consumo de resultados está documentado em [resultados de processamento](../docs/processing-results.md); exclusão distribuída continua proposta. Todas as operações exigem JWT válido, conta ativa e autorização por proprietário.

| Operação | Resultado |
| --- | --- |
| POST /videos, multipart + Idempotency-Key | 202 com id, originalName, sizeBytes, status e createdAt após original durável e commit de QUEUED/outbox |
| GET /videos | Lista paginada somente do dono |
| GET /videos/{id} | Estado e metadados do dono |
| GET /videos/{id}/download | Streaming do ZIP se COMPLETED e dentro de 24h |

Consultas usam 400, 401, 403, 404 e 503. Upload possui contrato detalhado no guia; download usa 400 para entrada inválida, 401 para autenticação inválida, 403 para conta sem acesso, 404 para recurso ausente/alheio, 409 para estado não concluído, 410 para resultado expirado e 503 para dependência necessária indisponível ou capacidade esgotada. Range é ignorado: a resposta é sempre o ZIP completo, sem retomada. Não revelar existência de vídeos de outro usuário.

Implementado publisher recuperável de VideoProcessingRequested na fila processing-work, desativado por padrão até existir consumidor. O consumo de ProcessingStarted, ProcessingCompleted e ProcessingFailed está implementado, com inbox e controle de versão. Notificações e exclusão distribuída permanecem planejadas. Envelope: eventId, eventType, schemaVersion, occurredAt, correlationId, ownerId, aggregateId e payload. Trabalho carrega referência imutável do objeto; resultado identifica tentativa e versão. Sem vídeos, senhas ou JWT em mensagens.

Identidade publica exclusão diretamente na fila deste consumidor e dos outros proprietários; outbox controla cada destino separadamente. SQS Standard exige deduplicação e tolerância a eventos fora de ordem. Os detalhes de schema serão fixados antes da implementação.
