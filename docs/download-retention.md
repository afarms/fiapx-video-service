# Reservas de download e retenção de resultados

Implementada a base transacional de coordenação. O endpoint de download, o streaming S3 e a rotina de exclusão ainda serão integrados; esta alteração não inicia downloads nem remove objetos.

O contrato previsto é download completo pela API autenticada durante as24h após a conclusão. Interrupção exige nova tentativa desde o início; não haverá retomada parcial nesta entrega. Novas chamadas verificam conta ativa, dono, COMPLETED e prazo original. Expiração mantém status e histórico.

## Persistência

`video_download_leases` registra token, vídeo, início, validade e deadline máximo de cada transferência admitida. `DownloadGateway` exige que o chamador autorize a conta antes da reserva; a persistência também filtra o dono. Referência privada validada contém bucket/chave/tamanho/checksum, sem integrar a resposta pública de consulta.

Reserva e renovação obtêm lock do vídeo, compartilhado com a seleção de limpeza. O relógio do PostgreSQL é consultado após o lock: chegar à API antes da expiração não garante admissão se a reserva só puder ocorrer depois. No limite exato expiresAt, nova reserva é recusada. Uma reserva já admitida pode renovar além desse prazo até seu deadline; reserva vencida não ressuscita. O streaming futuro deverá renovar e abortar ao perder posse, inclusive em falhas de rede ou prazo, e liberar reserva ao terminar.

Limpeza seleciona somente resultados COMPLETED expirados, sem reserva válida nem exclusão concluída. Usa `FOR UPDATE SKIP LOCKED`, token/lease de limpeza e horário da próxima tentativa. A camada S3 futura executará exclusão fora da transação e só confirmará sob token vigente; falha mantém obrigação de retry. Posse vencida é recuperável após crash e não pode confirmar operação de outra instância.

`result_deleted_at` indica confirmação de exclusão física. Não apaga referência histórica, status COMPLETED, datas, idempotência, inbox ou outbox. Limpeza física será assíncrona e poderá ocorrer depois das24h sem ampliar a disponibilidade. Originais, órfãos do worker e exclusão de conta têm regras próprias.

## Limites e testes

Migration incremental005 preserva as anteriores. Beans configurados centralmente em BeanConfig; gateway sem Spring/JPA no core. Todas as operações do adapter usam transação própria com timeout de10s, encerrada antes do retorno; não manter transação aberta durante rede.

Durations aceitas são segundos inteiros positivos até86400; deadline deve ser maior ou igual à lease. São validações técnicas do gateway, não promessa de download com duração de24h. O limite e heartbeat do streaming serão configurados na integração HTTP. Seleção de limpeza em lotes de1–100; o executor futuro deve respeitar o prazo de cada claim antes de iniciar I/O e não reservar trabalho além da capacidade disponível.

`make verify` testa gates e isolamento; `make integration` testa PostgreSQL real em schemas isolados: dono/expiração, reservas simultâneas, limpeza concorrente, deadline, restart, retry/fencing e rollback. AWS e identidade permanecem simuladas nesses testes. A limpeza física e a proteção efetiva de uma transferência HTTP ainda não são verificadas por esta base de persistência.
