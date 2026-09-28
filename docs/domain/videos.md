# Domínio de vídeos

Estado: consultas, upload, consumo de resultados, download autenticado e limpeza de ZIPs expirados implementados. Exclusão distribuída de conta e notificações continuam pendentes; validação integrada cloud ainda será executada. RF-01, RF-03, RF-05 e RT-01 orientam este domínio.

## Regras confirmadas

Proprietário identificado pelo JWT e pela situação atual da conta na identidade. USER e ADMIN só acessam os próprios vídeos; gestão administrativa de usuários não concede acesso a arquivos alheios. Novas chamadas falham se a conta estiver inativa/excluída, mesmo com JWT válido.

Entrada: MP4, AVI, MOV, MKV, WMV, FLV ou WebM; até 100.000.000 bytes e 300 segundos. Extensão não substitui validação da mídia. Extração de PNGs a uma imagem por segundo é a referência da base; processamento ocorre no serviço próprio.

Saída disponível até completedAt + 24h. Expiração de download não altera sucesso do processamento. Conta inativa ou excluída não admite novos downloads; uma transferência já autorizada pode terminar dentro do deadline operacional. A exclusão distribuída dos dados da conta ainda depende de implementação própria.

## Modelo atual e evolução prevista

Incremento atual: `Video` imutável com id/ownerId UUID, originalName (até 255 caracteres Java), originalObjectKey (até 1024), sizeBytes, createdAt e estados UPLOADING/QUEUED/PROCESSING/COMPLETED/FAILED, com datas de conclusão/expiração/falha e código de falha sanitizado. Validação de extensão aceita os sete formatos sem diferenciar maiúsculas/minúsculas; rejeita nome contendo caminho e tamanho fora de 1–100.000.000 bytes. Isso não valida conteúdo/duração. `VideoGateway` define inserção e consulta por id + ownerId. `VideoGatewayAdapter` implementa o contrato com Spring Data JPA e conversão domínio/entidade; consultas, upload e download autenticados estão implementados. Referência privada do ZIP e reservas ficam no gateway de download, sem exposição no DTO de consulta. UploadIntent representa chave permanente, fingerprint, token/prazo de tentativa e aceite.

Migration inicial cria `videos`, chave única de objeto e índice por dono/data/id. A migration 002 acrescenta QUEUED, intenção por dono/chave, tentativas e outbox sem reescrever a 001. As migrations 003 e 004 acrescentam resultados/inbox e reconciliação; a005 coordena reservas de download e claims de limpeza. Nenhuma FK aponta para banco de identidade. Retentativa de INSERT duplicado não sobrescreve o registro.

A entidade de persistência mantém versão, tentativa e referências privadas necessárias ao processamento; o domínio de consulta mantém metadados públicos. Outbox, inbox, intenção idempotente e reservas têm contratos próprios. Consulte [resultados](../processing-results.md) e [download e retenção](../download-retention.md).

Estados: UPLOADING -> QUEUED -> PROCESSING -> COMPLETED ou FAILED. Mídia inválida descoberta pelo worker termina em FAILED; a entrega do aviso ao usuário pertence à futura capacidade de notificações. Janela de disponibilidade é atributo separado do estado. Controle de versão e tentativa rejeita eventos antigos e transições regressivas.

## Consultas implementadas

Os casos de uso `GetVideoUseCase` e `ListVideosUseCase`, em `core/usecase`, usam somente o `VideoGateway`. A consulta por ID exige ID e proprietário; ausente ou alheio produz a mesma `VideoNotFoundException`. A listagem exige proprietário e `VideoPageRequest` (página >= 0, tamanho de 1 a 100), retornando `VideoPage` com itens imutáveis e total do proprietário. Ordenação fixa por criação decrescente e ID decrescente para desempate. Página fora do intervalo retorna itens vazios com total preservado. O limite de 100 é uma decisão técnica de paginação.

O adapter traduz paginação para Spring Data; filtro e contagem por proprietário pertencem ao repositório. Erros de acesso a dados continuam como `VideoPersistenceException`. UUIDs e requisição nulos são rejeitados antes de consultar. A entrada HTTP deriva o proprietário do JWT validado e consulta a identidade a cada chamada antes de acessar os vídeos. USER e ADMIN recebem o mesmo filtro por dono. A paginação não mantém snapshot entre chamadas concorrentes.

## Aceite e arquivos

Upload implementado via API autenticada para S3 privado, por streaming com limite de bytes; arquivo não é mantido inteiro em memória. Após persistência do original, transação grava QUEUED + outbox antes de 202. Duração/codecs serão inspecionados pelo processamento; 202 confirma aceite durável para validação/processamento, não garante mídia válida ou sucesso.

Requisição interrompida antes do commit não recebe aceite; chave de idempotência permite consultar/repetir sem duplicar trabalho. Objetos sem registro confirmado são reconciliados. A referência ao original precisa ser imutável por submissão.

Download completo implementado por streaming da API, sem URL direta reutilizável do objeto. Cada nova chamada verifica JWT, situação atual da conta, propriedade e expiresAt. Expiração não interrompe retroativamente uma transferência já autorizada; arquivos baixados não podem ser recuperados pelo sistema.

## Exclusão distribuída planejada

Ao receber UserDeletionRequested, persistir bloqueio local do ownerId, impedir novos registros/publicação e coordenar limpeza com execuções em encerramento por contrato. Reconciliação remove objetos produzidos por workers atrasados. Confirmar limpeza somente quando registros e objetos próprios tiverem sido removidos e não houver produtor autorizado pendente. Nenhuma consulta cruzada aos bancos dos outros serviços.

## Verificações e pendências

Testar isolamento de dois usuários; 100 MB e 300 segundos nos limites e acima deles; duplicação e ordem de eventos; commit sem publicação; perda de resposta após aceite; expiração do download; exclusão concorrente com upload/resultado; recuperação da outbox. Unitários e integração HTTP/PostgreSQL cobrem upload/idempotência/outbox, resultados, download, expiração e limpeza coordenada; S3/SQS e identidade são simulados nessa suíte. A validação de mídia/FFmpeg pertence ao worker. Fluxo integrado cloud e exclusão distribuída continuam pendentes; consulte o [roteiro de validação](../download-validation.md).
