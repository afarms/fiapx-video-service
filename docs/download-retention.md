# Download e retenção de resultados

`GET /videos/{id}/download` fornece o ZIP completo quando `DOWNLOAD_ENABLED=true`.
O endpoint valida JWT e consulta a situação atual da conta antes de reservar o resultado no PostgreSQL.
USER e ADMIN acessam somente os próprios vídeos COMPLETED, antes de `expiresAt=completedAt+24h`.
Não há URL pré-assinada nem exposição de bucket/chave. Interrupção exige nova requisição completa,
com nova validação de conta, dono e prazo. `Range` é ignorado: a resposta continua sendo 200 com o arquivo inteiro.

## HTTP

Sucesso: `application/zip`, `Content-Length` persistido, `Content-Disposition: attachment; filename="frames-{id}.zip"`,
`Cache-Control: private, no-store` e `Accept-Ranges: none`. O nome não usa conteúdo fornecido pelo usuário.

| Situação | Status |
| --- | --- |
| UUID inválido | 400 |
| JWT ausente/inválido/revogado | 401 |
| Conta sem acesso | 403 |
| Vídeo inexistente ou de outro dono | 404 |
| Vídeo ainda não concluído ou FAILED | 409 |
| Resultado expirado, após conferir dono | 410 |
| Dependência indisponível, objeto ausente/inconsistente ou capacidade esgotada | 503 |

Falhas anteriores ao envio do corpo retornam 503; o corpo pode ser vazio se a falha ocorrer na execução assíncrona.
Depois de iniciar a resposta, a conexão termina com corpo incompleto, sem acrescentar JSON ao ZIP.
Downloads HTTP/1.1 usam `Connection: close`, garantindo EOF imediato em falhas com Content-Length incompleto.
O header não é enviado em HTTP/2, que delimita o corpo pelo encerramento do stream.
O cliente deve conferir conclusão e tamanho da transferência e descartar downloads incompletos.
Falhas de download e expiração não alteram COMPLETED, completedAt ou expiresAt.

## Streaming e configuração

O serviço usa um buffer de 64 KiB por transferência, sem arquivo temporário nem materialização do ZIP de até 1 GiB.
Leitores S3 e renovações têm executores separados e capacidade limitada; a escrita servlet é não bloqueante.
Um cliente lento não mantém uma escrita bloqueante impedindo o monitor de validade.
O cliente S3 dedicado possui conexão/aquisição de 3s, leitura de 10s e limite de abertura da chamada de 15s.
O tamanho informado pelo S3 deve corresponder ao resultado persistido antes de enviar os headers de sucesso.

| Variável | Padrão | Limite |
| --- | --- | --- |
| DOWNLOAD_ENABLED | false | Habilitação explícita |
| DOWNLOAD_CONCURRENCY | 2 | 1–32 por instância; excesso recebe 503 |
| DOWNLOAD_LEASE_SECONDS | 120 | 30–86400 |
| DOWNLOAD_HEARTBEAT_SECONDS | 30 | Positivo, no máximo um terço da lease |
| DOWNLOAD_MAXIMUM_SECONDS | 1800 | Pelo menos a lease, no máximo 86400 |

Região e credenciais seguem `AWS_REGION` e `UPLOAD_AWS_PROFILE`/cadeia padrão AWS, inclusive quando upload e consumo
de resultados estão desativados. Não copiar credenciais para o `.env`. A role precisa de GetObject na referência
privada de `results/*`. A limpeza usa DeleteObject nesse mesmo prefixo quando habilitada separadamente.

O monitor usa relógio monotônico e começa a contar antes da admissão/renovação, conservadoramente em relação
ao relógio do PostgreSQL. Interrompe 5s antes do limite local da lease/deadline; por isso a duração útil pode ser menor
que o máximo configurado. A renovação não estende o deadline original. Falha ou perda de posse interrompe a leitura S3.
O monitor não depende do executor de renovação: uma chamada de banco lenta não impede a interrupção por prazo.
Desconexão, erro, timeout e desligamento abortam a leitura, sem drenar o restante do objeto.
A reserva só é liberada após encerrar o leitor. Se a liberação falhar, a validade persistida permite recuperação.

## Persistência e limpeza assíncrona

`video_download_leases` registra token, vídeo, início, validade e deadline de cada transferência.
Admissão, renovação e claim de limpeza compartilham o lock do vídeo. O relógio do banco é consultado depois do lock:
chegar à API antes da expiração não garante admissão se a reserva ocorrer depois. `now >= expiresAt` recusa nova reserva.
Uma transferência admitida pode renovar após expiresAt até seu deadline. Alteração posterior da conta não revoga
essa requisição já admitida; cada nova requisição revalida identidade. Reservas vencidas não ressuscitam.

A base transacional de limpeza seleciona resultados COMPLETED expirados, sem reserva válida nem exclusão concluída,
com `FOR UPDATE SKIP LOCKED`. Após adquirir o lock, um UPDATE condicional revalida critérios e reservas em nova
instrução sob READ COMMITTED. Assim, também enxerga reservas confirmadas depois do snapshot inicial da seleção.
Token e lease impedem confirmação por uma instância antiga. Falha permite retry; crash permite recuperar a posse.

O executor assíncrono exclui a referência exata no S3 e confirma pelo token vigente. `result_deleted_at` registra a confirmação sem apagar referência
histórica, status, datas, idempotência, inbox ou outbox. Atraso da limpeza não amplia a disponibilidade de 24h.
Originais, órfãos do worker e exclusão de conta têm regras próprias.

Migration incremental 005 preserva as anteriores. As operações de reserva/limpeza têm transações próprias,
com timeout de 10s, encerradas antes da rede. Beans ficam em BeanConfig; o core permanece sem Spring/JPA/SDK.

## Validação

`make verify` executa testes unitários, isolamento do core e gates de cobertura de 90%.
`make integration` usa HTTP e PostgreSQL reais em schemas isolados, com identidade e S3 simulados:
bytes/headers, autorização, expiração, ausência de objeto, renovação durante transferência, proteção contra limpeza,
desconexão de cliente, reservas concorrentes, fencing, restart e rollback. Testes unitários exercitam falhas e capacidade.
O comportamento com AWS, proxies e implantação cloud ainda precisa ser validado com a infraestrutura completa.
O [roteiro de validação](download-validation.md) consolida os cenários locais e o ensaio cloud pendente.

## Operação da limpeza

A limpeza é independente do endpoint de download, upload e consumidores de filas. Permanece desativada por padrão.
Após o intervalo inicial, cada rodada processa até o limite configurado, adquirindo um claim por vez.
Não reserva um lote inteiro que possa vencer enquanto aguarda leitura/exclusão. Um scheduler dedicado evita bloquear
publicação e consumo de mensagens. Réplicas coordenam-se pelos claims PostgreSQL.

| Variável | Padrão | Limite |
| --- | --- | --- |
| RESULT_CLEANUP_ENABLED | false | Habilitação explícita após configurar IAM |
| RESULT_CLEANUP_DELAY_MS | 60000 | Pelo menos1000; intervalo após cada rodada |
| RESULT_CLEANUP_LEASE_SECONDS | 120 | 45–86400 |
| RESULT_CLEANUP_RETRY_SECONDS | 300 | 1–86400 |
| RESULT_CLEANUP_BATCH_SIZE | 10 | 1–100 por rodada; claims individuais |

DeleteObject tem timeout total de15s e tentativa de10s. Se o tempo monotônico gasto para adquirir o claim consumir
sua margem de20s, o executor adia sem iniciar S3. Falha mantém a obrigação no banco e agenda retry; falha também
no banco deixa o claim expirar. Confirmação é condicionada ao token e à validade. Crash após S3 e antes do commit
repete a exclusão idempotente; NoSuchKey é sucesso, mas acesso negado ou bucket ausente não são.

A policy Terraform preparada para a role de vídeos permite DeleteObject somente no bucket de mídia em results/*,
sem listagem, escrita de resultados ou DeleteObjectVersion. A aplicação da policy não é feita pelo build local.
O bucket opera com versionamento suspenso; esta rotina não gerencia versões históricas nem altera essa configuração.

A integração local cobre retry/restart, falha entre S3 e confirmação, concorrência entre executores, preservação do
histórico e scheduler com as outras funcionalidades desativadas. S3 é simulado; exclusão real e IAM efetivo aguardam cloud.
