# Validação de download e retenção

Download e limpeza têm testes locais de autorização, streaming, expiração e coordenação PostgreSQL.
Os testes não substituem a validação do S3, da identidade real e dos proxies usados na implantação.
O [contrato operacional](download-retention.md) define flags, limites e respostas esperadas.

## Evidência local reproduzível

- `make verify`: unitários, core compilado sem frameworks e gates de90% de linhas/branches.
- `make integration`: HTTP e PostgreSQL reais, schemas exclusivos; identidade/S3/SQS simulados.
- `make image config-check`: empacotamento e configuração; não executa testes dentro do build Docker.
- No repositório de infraestrutura, `make verify`: Terraform com backend desabilitado e mocks.

As regressões incluem o snapshot antigo da seleção de limpeza, revalidação após lock, duas reservas ativas,
deadline não prorrogável, renovação bloqueada, Range ignorado, desconexão de cliente e corpo truncado após headers.
Também cobrem retry/restart, exclusão S3 seguida de falha SQL, token antigo e scheduler independente.
A CI do serviço executa unitários/cobertura e depois imagem; a integração PostgreSQL é executada separadamente.

## Preparação do ensaio cloud

Executar depois que identidade, vídeos, processamento, bancos, filas, S3 e caminho HTTP público estiverem implantados.
Usar duas contas descartáveis, arquivos sintéticos e ambiente isolado. Registrar versões/imagens, flags, identidade AWS
do workload, limites de duração/custo, estado inicial e responsáveis. Não habilitar limpeza em carga compartilhada
como parte de um ensaio sem escopo definido. O workflow Terraform e o acesso efetivo do workload são provas distintas.

Manter manifesto com IDs de vídeos/eventos, referências exatas de objetos e timestamps. JWTs, credenciais,
dados pessoais e arquivos privados não pertencem aos relatórios. A preparação deve especificar as injeções de falha
e a remoção dos dados sintéticos antes da execução; este roteiro não executa operações remotas.

| Cenário | Resultado a registrar |
| --- | --- |
| Upload → processamento → download | ZIP íntegro com PNGs; tamanho/hash conferidos; status e prazo original preservados |
| Duas contas, USER/ADMIN, token inválido, conta inativa e identidade indisponível | Sem acesso indevido ao S3; respostas do contrato e nenhuma exposição da referência privada |
| Expiração e nova tentativa após interrupção | Novo acesso recusado em expiresAt; Range não retoma; nova requisição transfere tudo e revalida autorização |
| Cliente lento/desconectado, falha S3/SQL e deadline | Memória/conexões/threads limitadas, reserva encerrada, corpo truncado detectado sem JSON |
| HTTP/1.1 e HTTP/2 através do proxy real | Headers e término de transferência corretos; nenhuma resposta parcial aceita como completa |
| Download admitido antes da expiração com dois pods e limpeza concorrente | Reserva renovada protege o objeto; limpeza ocorre somente após liberação/expiração da posse |
| Falha/restart antes e depois de DeleteObject | Retry idempotente, confirmação por token vigente, histórico preservado e nenhuma exclusão fora do manifesto |

Se forem usados timestamps controlados para simular24h, fazê-lo somente no dataset isolado e identificar a simulação
na evidência. Não relatar espera real de24h nem sucesso em AWS com base em doubles locais.
Concluir com comparação do estado final ao manifesto, limpeza exata dos dados de teste e registro de qualquer cenário
não executado. Exclusão distribuída de conta e limpeza de órfãos do worker são responsabilidades separadas.
