# Clean Architecture

O core define regras e contratos sem importar Spring, JPA ou infraestrutura. As dependências apontam para o core. Spring é usado somente em infrastructure, incluindo a inicialização da aplicação.

```text
br.com.fiap.fiapx.video
├── core
│   ├── domain/Video
│   ├── gateway/VideoGateway
│   └── exception/VideoPersistenceException, VideoConflictException
└── infrastructure
    ├── VideoApplication
    ├── config/BeanConfig
    └── persistence
        ├── entity/VideoEntity
        ├── mapper/VideoMapper
        ├── adapter/VideoGatewayAdapter
        └── repository/SpringVideoRepository
```

- `VideoGateway`: contrato do core, recebe/devolve objetos do domínio e tipos Java. Não expõe entidades, Page/Pageable ou tipos do Spring.
- `VideoEntity`: mapeamento JPA da tabela videos, com construtor sem argumentos para hidratação e UUID definido pelo domínio.
- `VideoMapper`: converte todos os campos domínio/entidade, revalida as invariantes ao ler e rejeita status que o modelo inicial ainda não representa.
- `SpringVideoRepository`: interface que estende JpaRepository<VideoEntity, UUID>. O Spring Data fornece a implementação. A consulta declarada usa id e ownerId.
- `VideoGatewayAdapter`: implementa VideoGateway; recebe SpringVideoRepository e VideoMapper por construtor em atributos final. Converte resultados e traduz falhas de acesso para VideoPersistenceException; violações de integridade na inserção tornam-se VideoConflictException. A causa original é preservada para diagnóstico.

## Composição dos beans

`infrastructure/config/BeanConfig` centraliza a criação dos beans próprios com `@Configuration(proxyBeanMethods = false)` e métodos `@Bean`. Registra VideoMapper e VideoGateway, construído como VideoGatewayAdapter com o repositório e o mapper recebidos por parâmetro. Mapper e adapter não têm @Component, @Service ou @Repository; a injeção é por construtor.

SpringVideoRepository continua sendo um proxy criado pelo Spring Data JPA; não deve ser instanciado manualmente. BeanConfig recebe esse bean ao montar o gateway. Entidades mantêm suas anotações JPA de mapeamento, que não são declaração de beans. A inicialização Spring fica em VideoApplication e descobre BeanConfig na infraestrutura. Futuros casos de uso e adapters próprios serão compostos nesse mesmo ponto.

## Inserção e schema

O contrato atual é insert, não upsert. Como o UUID já existe antes de salvar, VideoEntity implementa Persistable para informar que uma instância recém-mapeada é nova. Callbacks PostPersist/PostLoad alteram apenas o indicador técnico transitório. Isso orienta Spring Data a usar persist em vez de merge, preservando a tentativa de INSERT e as restrições de unicidade. O adapter usa saveAndFlush para provocar o flush na operação; a transação atual pertence ao método do repositório.

Não há pré-consulta exists para tratar duplicidade: a restrição do banco é a autoridade sob concorrência. Futuras operações de atualização devem ter contrato próprio e considerar o ciclo de vida da entidade; não reaproveitar insert como update. Quando houver vídeo + outbox, a infraestrutura deverá delimitar uma única transação envolvendo ambos.

Liquibase permanece responsável pelo schema. `ddl-auto=validate` só valida o mapeamento; não cria/altera tabelas. `open-in-view=false` impede depender de sessão JPA aberta na camada web. A migration inicial permanece inalterada.

Fonte: [Spring Data JPA — Persisting Entities](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html).

## Verificação e limites

Unitários cobrem adapter com repositório simulado, mapper real, falhas e indicador de entidade nova. CoreIsolationTest compila as fontes do core apenas com JDK 21, sem bibliotecas externas ou classes da infraestrutura. Não há container Spring nesses testes.

Mocks não comprovam geração da consulta, execução dos callbacks pelo Hibernate, transações ou schema real. Esses pontos dependem dos testes posteriores com PostgreSQL. Casos de uso e APIs serão criados nas capacidades funcionais; não há classes vazias para simular essas camadas.
