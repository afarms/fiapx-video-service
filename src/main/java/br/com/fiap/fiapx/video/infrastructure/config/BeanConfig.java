package br.com.fiap.fiapx.video.infrastructure.config;

import br.com.fiap.fiapx.video.core.gateway.VideoGateway;
import br.com.fiap.fiapx.video.core.usecase.GetVideoUseCase;
import br.com.fiap.fiapx.video.core.usecase.ListVideosUseCase;
import br.com.fiap.fiapx.video.infrastructure.persistence.adapter.VideoGatewayAdapter;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.VideoMapper;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import br.com.fiap.fiapx.video.core.gateway.AccountAccessGateway;
import br.com.fiap.fiapx.video.core.usecase.AuthorizeVideoAccessUseCase;
import br.com.fiap.fiapx.video.infrastructure.identity.IdentityHttpAdapter;
import br.com.fiap.fiapx.video.infrastructure.security.VideoJwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import br.com.fiap.fiapx.video.core.gateway.UploadGateway;
import br.com.fiap.fiapx.video.core.gateway.OriginalStorageGateway;
import br.com.fiap.fiapx.video.core.usecase.UploadVideoUseCase;
import br.com.fiap.fiapx.video.infrastructure.persistence.adapter.UploadGatewayAdapter;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringOutboxRepository;
import br.com.fiap.fiapx.video.infrastructure.web.UploadFiles;
import br.com.fiap.fiapx.video.infrastructure.web.MultipartSpool;
import br.com.fiap.fiapx.video.infrastructure.security.UploadAdmissionFilter;
import br.com.fiap.fiapx.video.infrastructure.storage.*;
import br.com.fiap.fiapx.video.infrastructure.messaging.OutboxDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import br.com.fiap.fiapx.video.core.gateway.ProcessingResultsGateway;
import br.com.fiap.fiapx.video.infrastructure.persistence.adapter.ProcessingResultsAdapter;
import br.com.fiap.fiapx.video.infrastructure.messaging.ProcessingResultDecoder;
import br.com.fiap.fiapx.video.infrastructure.messaging.ProcessingResultsConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class BeanConfig {
    @Bean
    @ConditionalOnProperty(name = "download.enabled", havingValue = "true")
    public br.com.fiap.fiapx.video.core.usecase.DownloadVideoUseCase downloadVideoUseCase(
            AuthorizeVideoAccessUseCase authorize, br.com.fiap.fiapx.video.core.gateway.DownloadGateway gateway,
            @Value("${download.lease-seconds:120}") long lease, @Value("${download.maximum-seconds:1800}") long maximum) {
        return new br.com.fiap.fiapx.video.core.usecase.DownloadVideoUseCase(authorize, gateway,
                Duration.ofSeconds(lease), Duration.ofSeconds(maximum));
    }

    @Bean
    @ConditionalOnProperty(name = "download.enabled", havingValue = "true")
    public DownloadStorage downloadStorage(AwsCredentialsProvider credentials,
            @Value("${upload.region:us-east-1}") String region) {
        return new S3DownloadStorage(S3Client.builder().region(Region.of(region)).credentialsProvider(credentials)
                .httpClientBuilder(software.amazon.awssdk.http.apache.ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(10))
                        .connectionAcquisitionTimeout(Duration.ofSeconds(3)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))).build());
    }

    @Bean
    @ConditionalOnProperty(name = "download.enabled", havingValue = "true")
    public br.com.fiap.fiapx.video.infrastructure.web.DownloadTransfers downloadTransfers(
            br.com.fiap.fiapx.video.core.gateway.DownloadGateway gateway, DownloadStorage storage,
            @Value("${download.concurrency:2}") int concurrency,
            @Value("${download.lease-seconds:120}") long lease,
            @Value("${download.maximum-seconds:1800}") long maximum,
            @Value("${download.heartbeat-seconds:30}") long heartbeat) {
        return new br.com.fiap.fiapx.video.infrastructure.web.DownloadTransfers(gateway, storage, concurrency,
                Duration.ofSeconds(lease), Duration.ofSeconds(maximum), Duration.ofSeconds(heartbeat));
    }
    @Bean
    public br.com.fiap.fiapx.video.infrastructure.persistence.mapper.DownloadMapper downloadMapper() {
        return new br.com.fiap.fiapx.video.infrastructure.persistence.mapper.DownloadMapper();
    }

    @Bean
    public br.com.fiap.fiapx.video.core.gateway.DownloadGateway downloadGateway(
            br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringDownloadRepository repository,
            br.com.fiap.fiapx.video.infrastructure.persistence.mapper.DownloadMapper mapper,
            PlatformTransactionManager manager) {
        var tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
        return new br.com.fiap.fiapx.video.infrastructure.persistence.adapter.DownloadGatewayAdapter(repository, mapper, tx);
    }

    @Bean
    @ConditionalOnProperty(name = {"upload.enabled", "upload.publisher-enabled", "upload.processing-reconcile-enabled"}, havingValue = "true")
    public br.com.fiap.fiapx.video.infrastructure.messaging.ProcessingReconciler processingReconciler(
            SpringOutboxRepository repository, PlatformTransactionManager manager) {
        var tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        return new br.com.fiap.fiapx.video.infrastructure.messaging.ProcessingReconciler(repository, tx);
    }

    @Bean
    public ProcessingResultsGateway processingResultsGateway(SpringVideoRepository repository,VideoMapper mapper,
            PlatformTransactionManager manager,JsonMapper json) {
        var tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        return new ProcessingResultsAdapter(repository,mapper,tx,json);
    }

    @Bean
    @ConditionalOnProperty(name="results.enabled",havingValue="true")
    public ProcessingResultsConsumer processingResultsConsumer(SqsClient sqs,ProcessingResultsGateway gateway,JsonMapper json,
            @Value("${results.queue-url}") String queue,@Value("${upload.bucket}") String bucket) {
        return new ProcessingResultsConsumer(sqs,queue,new ProcessingResultDecoder(json,bucket),gateway);
    }

    @Bean
    @ConditionalOnProperty(name="results.enabled",havingValue="true")
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(3); scheduler.setThreadNamePrefix("video-messaging-");
        return scheduler;
    }

    @Bean
    public Clock clock() { return Clock.systemUTC(); }

    @Bean
    public AuthorizeVideoAccessUseCase authorizeVideoAccessUseCase(AccountAccessGateway gateway) {
        return new AuthorizeVideoAccessUseCase(gateway);
    }

    @Bean
    public RestClient identityClient(@Value("${identity.url}") URI url,
            @Value("${identity.service-key}") String key,
            @Value("${identity.connect-timeout-ms:2000}") int connectTimeout,
            @Value("${identity.read-timeout-ms:3000}") int readTimeout) {
        if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null
                || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null) {
            throw new IllegalArgumentException("identity.url must be an HTTP(S) URL without credentials, query or fragment");
        }
        if (key.isBlank() || key.length() < 32 || connectTimeout <= 0 || readTimeout <= 0) {
            throw new IllegalArgumentException("Identity credential and timeouts are required");
        }
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readTimeout));
        return RestClient.builder().baseUrl(url.toString()).defaultHeader("X-Service-Key", key)
                .requestFactory(factory).build();
    }

    @Bean
    public AccountAccessGateway accountAccessGateway(RestClient identityClient) {
        return new IdentityHttpAdapter(identityClient);
    }

    @Bean
    public RSAPublicKey publicKey(@Value("${identity.jwt.public-key}") Resource file) throws Exception {
        try (var input = file.getInputStream()) { return RsaKeyConverters.x509().convert(input); }
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey key, Clock clock, @Value("${identity.jwt.issuer}") String issuer,
            @Value("${identity.jwt.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withPublicKey(key).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer), new VideoJwtValidator(audience, clock)));
        return decoder;
    }

    @Bean
    public io.swagger.v3.oas.models.OpenAPI openApi() {
        return new io.swagger.v3.oas.models.OpenAPI().components(new io.swagger.v3.oas.models.Components()
                .addSecuritySchemes("bearerAuth", new io.swagger.v3.oas.models.security.SecurityScheme()
                        .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<UploadFiles> files,
                                                   AuthorizeVideoAccessUseCase authorize) throws Exception {
        if (files.getIfAvailable() != null) {
            http.addFilterAfter(new UploadAdmissionFilter(authorize, files.getObject()), BearerTokenAuthenticationFilter.class);
        }
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/swagger-ui.html",
                        "/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> {})).build();
    }

    @Bean
    public GetVideoUseCase getVideoUseCase(VideoGateway gateway) {
        return new GetVideoUseCase(gateway);
    }

    @Bean
    public ListVideosUseCase listVideosUseCase(VideoGateway gateway) {
        return new ListVideosUseCase(gateway);
    }

    @Bean
    public VideoMapper videoMapper() {
        return new VideoMapper();
    }

    @Bean
    public VideoGateway videoGateway(SpringVideoRepository repository, VideoMapper mapper) {
        return new VideoGatewayAdapter(repository, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "upload.enabled", havingValue = "true")
    public TransactionTemplate uploadTransactions(PlatformTransactionManager manager) {
        var template = new TransactionTemplate(manager);
        template.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(180);
        return template;
    }

    @Bean
    @ConditionalOnExpression("${upload.enabled:false} or ${results.enabled:false} or ${download.enabled:false}")
    public AwsCredentialsProvider uploadCredentials(@Value("${upload.aws-profile:}") String profile) {
        return profile.isBlank() ? DefaultCredentialsProvider.builder().build() : ProfileCredentialsProvider.create(profile);
    }

    @Bean
    @ConditionalOnProperty(name = "upload.enabled", havingValue = "true")
    public S3Client uploadS3(AwsCredentialsProvider credentials, @Value("${upload.region:us-east-1}") String region) {
        return S3Client.builder().region(Region.of(region)).credentialsProvider(credentials)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(120)).apiCallAttemptTimeout(Duration.ofSeconds(60))).build();
    }

    @Bean
    @ConditionalOnExpression("${upload.enabled:false} or ${results.enabled:false}")
    public SqsClient uploadSqs(AwsCredentialsProvider credentials, @Value("${upload.region:us-east-1}") String region) {
        return SqsClient.builder().region(Region.of(region)).credentialsProvider(credentials)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
    }

    @Bean
    @ConditionalOnProperty(name = "upload.enabled", havingValue = "true")
    public UploadFiles uploadFiles(@Value("${upload.temp-directory:.local/uploads}") Path directory,
                                   @Value("${upload.concurrency:2}") int concurrency,
                                   @Value("${upload.disk-reserve-bytes:500000000}") long reserve, Clock clock) throws java.io.IOException {
        return new UploadFiles(directory, concurrency, reserve, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "upload.enabled", havingValue = "true")
    public MultipartSpool multipartSpool(UploadFiles files) throws java.io.IOException {
        return new MultipartSpool(files.directory());
    }

    @Bean
    @ConditionalOnProperty(name = "upload.enabled", havingValue = "true")
    public jakarta.servlet.MultipartConfigElement uploadMultipartConfig(MultipartSpool spool) {
        // Use the same filesystem whose free space is checked before multipart parsing.
        return new jakarta.servlet.MultipartConfigElement(spool.directory().toString(), 100_000_000, 101_000_000, 0);
    }

    @Bean
    @ConditionalOnProperty(name = "upload.enabled", havingValue = "true")
    public UploadGateway uploadGateway(SpringVideoRepository repository, VideoMapper mapper,
                                       TransactionTemplate uploadTransactions, JsonMapper json) {
        return new UploadGatewayAdapter(repository, mapper, uploadTransactions, json);
    }

    @Bean
    @ConditionalOnProperty(name = "upload.enabled", havingValue = "true")
    public OriginalStorageGateway originalStorage(S3Client client, @Value("${upload.bucket}") String bucket) {
        return new S3OriginalStorageAdapter(client, bucket);
    }

    @Bean
    @ConditionalOnProperty(name = "upload.enabled", havingValue = "true")
    public UploadVideoUseCase uploadVideoUseCase(UploadGateway gateway, OriginalStorageGateway storage,
            @Value("${upload.bucket}") String bucket, @Value("${upload.lease-seconds:300}") long lease) {
        if (lease <= 120) throw new IllegalArgumentException("Upload lease must exceed the S3 call timeout");
        return new UploadVideoUseCase(gateway, storage, bucket, Duration.ofSeconds(lease));
    }

    @Bean
    @ConditionalOnProperty(name = {"upload.enabled", "upload.publisher-enabled"}, havingValue = "true")
    public OutboxDispatcher outboxDispatcher(SpringOutboxRepository repository, TransactionTemplate uploadTransactions,
            SqsClient client, @Value("${upload.queue-url}") String queueUrl) {
        return new OutboxDispatcher(repository, uploadTransactions, client, queueUrl);
    }

    @Bean
    @ConditionalOnProperty(name = {"upload.enabled", "upload.cleanup-enabled"}, havingValue = "true")
    public UploadReconciler uploadReconciler(SpringVideoRepository repository, TransactionTemplate uploadTransactions,
            S3Client client, @Value("${upload.bucket}") String bucket, Clock clock, UploadFiles files) {
        return new UploadReconciler(repository, uploadTransactions, client, bucket, clock, files);
    }
}
