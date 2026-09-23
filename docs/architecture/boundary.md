# Limite arquitetural do serviço

Vídeos é proprietário do ciclo público da submissão e da autorização de acesso aos arquivos. O worker é outro microsserviço: executa FFmpeg e informa resultados. Identidade responde por contas/permissões atuais; notificações persiste avisos de falha.

Um repositório, build, imagem, Deployment e banco lógico próprios. Migrations deste domínio ficam neste serviço. Infraestrutura AWS e documentação integrada ficam em fiapx-infra. Nenhuma biblioteca de entidades JPA compartilhada.

Dependências propostas: PostgreSQL, S3 privado, SQS e API interna de identidade. Falha da verificação de conta bloqueia novas operações protegidas. O banco é acessado somente com credencial do domínio.
