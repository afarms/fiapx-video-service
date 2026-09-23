# Contratos de vídeos — proposta

Ainda não são OpenAPI ou schemas executáveis. Todas as operações exigem JWT válido, conta ativa e autorização por proprietário.

| Operação | Resultado |
| --- | --- |
| POST /videos, multipart + Idempotency-Key | 202 com videoId após original durável e commit de QUEUED/outbox |
| GET /videos | Lista paginada somente do dono |
| GET /videos/{id} | Estado e metadados do dono |
| GET /videos/{id}/download | Streaming do ZIP se COMPLETED e dentro de 24h |

Erros propostos: 400 entrada inválida, 401 autenticação inválida, 403 conta sem acesso, 404 recurso ausente/alheio, 409 conflito de idempotência/estado, 410 resultado expirado, 413 tamanho excedido, 503 dependência necessária indisponível. Não revelar existência de vídeos de outro usuário.

Publica VideoProcessingRequested na fila processing-work e VideoFailed na notifications-events. Consome eventos de início/resultado/exclusão na videos-events. Envelope: eventId, eventType, schemaVersion, occurredAt, correlationId, ownerId, aggregateId e payload. Trabalho carrega referência imutável do objeto; resultado identifica tentativa e versão. Sem vídeos, senhas ou JWT em mensagens.

Identidade publica exclusão diretamente na fila deste consumidor e dos outros proprietários; outbox controla cada destino separadamente. SQS Standard exige deduplicação e tolerância a eventos fora de ordem. Os detalhes de schema serão fixados antes da implementação.
