# Domínio de vídeos

Estado: modelo inicial no core e adaptador Spring Data JPA na infraestrutura implementados; fluxo funcional abaixo ainda em evolução. RF-01, RF-03, RF-05 e RT-01 orientam este domínio.

## Regras confirmadas

Proprietário identificado pelo JWT e pela situação atual da conta na identidade. USER e ADMIN só acessam os próprios vídeos; gestão administrativa de usuários não concede acesso a arquivos alheios. Novas chamadas falham se a conta estiver inativa/excluída, mesmo com JWT válido.

Entrada: MP4, AVI, MOV, MKV, WMV, FLV ou WebM; até 100.000.000 bytes e 300 segundos. Extensão não substitui validação da mídia. Extração de PNGs a uma imagem por segundo é a referência da base; processamento ocorre no serviço próprio.

Saída disponível até completedAt + 24h. Expiração de download não altera sucesso do processamento. A exclusão definitiva de usuário prevalece sobre a disponibilidade normal.

## Modelo proposto

Incremento atual: `Video` imutável com id/ownerId UUID, originalName (até 255 caracteres Java), originalObjectKey (até 1024), sizeBytes e createdAt. Estado inicial UPLOADING. Validação de extensão aceita os sete formatos sem diferenciar maiúsculas/minúsculas; rejeita nome contendo caminho e tamanho fora de 1–100.000.000 bytes. Isso não valida conteúdo/duração. `VideoGateway` define inserção e consulta por id + ownerId. `VideoGatewayAdapter` implementa o contrato com Spring Data JPA e conversão domínio/entidade; não há endpoint público nem autorização implementada.

Migration inicial cria `videos`, chave única de objeto e índice por dono/data/id. Apenas UPLOADING é permitido nesta etapa; a ampliação abaixo virá por novos changesets junto dos contratos de transição. Nenhuma FK aponta para banco de identidade. Retentativa de INSERT duplicado não sobrescreve o registro.

Video: id UUID, ownerId imutável, originalObjectKey, resultObjectKey, originalName, sizeBytes, durationSeconds, status, attempt, version, createdAt, completedAt, expiresAt e errorCode sanitizado. Tabelas adicionais: outbox por destino, inbox de eventos processados, chaves de idempotência e controle de exclusões.

Estados: UPLOADING -> QUEUED -> PROCESSING -> COMPLETED ou FAILED. Mídia inválida descoberta pelo worker termina em FAILED e gera aviso. Janela de disponibilidade é atributo separado do estado. Controle de versão e tentativa rejeita eventos antigos e transições regressivas.

## Aceite e arquivos

Proposta: upload via API autenticada para S3 privado, por streaming com limite de bytes; arquivo não é mantido inteiro em memória. Após persistência do original, transação grava QUEUED + outbox antes de 202. Duração/codecs serão inspecionados pelo processamento; 202 confirma aceite durável para validação/processamento, não garante mídia válida ou sucesso.

Requisição interrompida antes do commit não recebe aceite; chave de idempotência permite consultar/repetir sem duplicar trabalho. Objetos sem registro confirmado são reconciliados. A referência ao original precisa ser imutável por submissão.

Download proposto por streaming da API, sem URL direta reutilizável do objeto. Cada nova chamada verifica JWT, situação atual da conta, propriedade e expiresAt. Expiração não interrompe retroativamente uma transferência já autorizada; arquivos baixados não podem ser recuperados pelo sistema.

## Exclusão

Ao receber UserDeletionRequested, persistir bloqueio local do ownerId, impedir novos registros/publicação e coordenar limpeza com execuções em encerramento por contrato. Reconciliação remove objetos produzidos por workers atrasados. Confirmar limpeza somente quando registros e objetos próprios tiverem sido removidos e não houver produtor autorizado pendente. Nenhuma consulta cruzada aos bancos dos outros serviços.

## Verificações previstas

Testar isolamento de dois usuários; 100 MB e 300 segundos nos limites e acima deles; duplicação e ordem de eventos; commit sem publicação; perda de resposta após aceite; expiração do download; exclusão concorrente com upload/resultado; recuperação da outbox. Ainda não executadas.
