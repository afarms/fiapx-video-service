# Recuperação de trabalho e resultados

O serviço conserva o envelope original de cada trabalho aceito na `video_outbox`. Com `UPLOAD_ENABLED=true`, `UPLOAD_PUBLISHER_ENABLED=true` e `PROCESSING_RECONCILE_ENABLED=true`, uma varredura a cada 60 segundos reagenda até cem pedidos cujo vídeo está QUEUED ou PROCESSING e cuja última publicação bem-sucedida ocorreu há pelo menos seis horas. O intervalo não é um SLA de processamento. A reconciliação não depende de consultar a fila nem a situação atual da conta.

A seleção bloqueia vídeo e outbox com `FOR UPDATE SKIP LOCKED`. Seleção e agendamento usam uma transação independente de até dez segundos, sem chamada SQS. Réplicas não agendam simultaneamente o mesmo registro; UPLOADING, COMPLETED e FAILED são excluídos. Uma transição terminal já bloqueada por outra transação é reavaliada numa próxima varredura. Um pedido agendado antes da chegada do resultado pode ainda ser enviado; a deduplicação do worker absorve essa entrega.

O publisher existente envia o mesmo `eventId`, correlação, fingerprint e payload. Falhas conservam o pedido pendente com backoff; novas varreduras não reiniciam a espera nem roubam seu lease. Após publicação bem-sucedida, inicia-se outra janela de seis horas. Se o worker já terminou, a reentrega agenda somente o resultado terminal persistido, sem novo FFmpeg ou alteração de versão, `completedAt` e `expiresAt`. Uma conclusão já expirada continua COMPLETED.

A migration 004 acrescenta `reconciliation_count` e `reconciled_at` sem modificar migrations anteriores. Essas colunas e o aviso `Processing result overdue; request rescheduled` permitem identificar recorrência; republicar não significa resolver o incidente. O envelope e a intenção aceita não têm expiração automática em 24 horas.

## Diagnóstico e DLQ

1. Correlacionar `eventId`, vídeo e correlação nos registros de cada serviço, sem copiar payloads privados ou credenciais para logs. Cada serviço consulta somente seu próprio banco.
2. Conferir consumidor/publicador habilitados, conectividade, permissões, pendências da outbox e erros de schema/identidade. Uma DLQ não representa um estado FAILED do vídeo. Mensagem malformada não permite inventar um job.
3. Corrigir a causa antes de um redrive operacional autorizado. Preservar o envelope original; não criar outro `eventId`, alterar fingerprint ou zerar tentativas de mídia. Aplicações não consomem DLQs automaticamente.
4. Para um pedido identificável ausente da fila, manter os registros duráveis e permitir a reconciliação. Se o worker não terminou, recupera o trabalho; se terminou, republica o resultado. Confirmar estado terminal e consumo durável em vídeos, não apenas uma fila vazia.
5. Investigar qualquer DLQ não vazia e qualquer repetição (`reconciliation_count > 1`). Configurar alarmes operacionais para DLQ, erros repetidos de publicação/consumo e reconciliação sem progresso. Esses alarmes cloud ainda precisam ser provisionados/verificados; o código fornece logs e contagem persistida.

Consulta de diagnóstico no banco de vídeos (somente leitura):

```sql
SELECT o.event_id, o.video_id, v.status, o.published_at, o.available_at,
       o.reconciled_at, o.reconciliation_count
FROM video_outbox o JOIN videos v ON v.id = o.video_id
WHERE v.status IN ('QUEUED', 'PROCESSING')
ORDER BY o.reconciliation_count DESC, o.published_at NULLS FIRST;
```

`make integration` valida agendamento concorrente, rollback, preservação do envelope, retomada após reinício e exclusão de terminais com PostgreSQL real e transporte simulado. A suíte do worker valida reenvio terminal e ausência de ACK quando o agendamento reverte. Retenção e redrive SQS reais exigem ensaio isolado autorizado; estes testes locais não apagam nem enviam mensagens AWS.
