package br.com.fiap.fiapx.video.infrastructure.persistence.adapter;

import br.com.fiap.fiapx.video.core.exception.*;
import br.com.fiap.fiapx.video.core.gateway.DownloadGateway;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.DownloadMapper;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringDownloadRepository;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

public final class DownloadGatewayAdapter implements DownloadGateway {
    private final SpringDownloadRepository repository;
    private final DownloadMapper mapper;
    private final TransactionTemplate transactions;
    public DownloadGatewayAdapter(SpringDownloadRepository repository, DownloadMapper mapper, TransactionTemplate transactions) {
        this.repository = repository; this.mapper = mapper; this.transactions = transactions;
    }
    public Lease reserve(UUID video, UUID owner, Duration lease, Duration maximumDuration) {
        validate(lease); validate(maximumDuration); Objects.requireNonNull(video); Objects.requireNonNull(owner);
        if (maximumDuration.compareTo(lease) < 0) throw new IllegalArgumentException("Deadline precedes lease");
        return committed(() -> {
            var row = repository.lock(video).filter(r -> owner.equals(r.getOwnerId())).orElseThrow(VideoNotFoundException::new);
            if (!"COMPLETED".equals(row.getStatus())) throw new DownloadException(DownloadException.Reason.NOT_READY);
            Instant now = repository.now(); // Never use a timestamp taken before waiting for the row lock.
            if (!now.isBefore(row.getExpiresAt()) || row.getResultDeletedAt() != null || row.getResultCleanupToken() != null)
                throw new DownloadException(DownloadException.Reason.EXPIRED);
            var artifact = mapper.artifact(row);
            UUID token = UUID.randomUUID(); Instant until = now.plus(lease), deadline = now.plus(maximumDuration);
            changed(repository.insertLease(token, video, now, until, deadline));
            return new Lease(token, artifact, until, deadline);
        });
    }
    public Optional<Instant> renew(UUID video, UUID token, Duration lease) {
        validate(lease); Objects.requireNonNull(video); Objects.requireNonNull(token);
        return committed(() -> {
            var row = repository.lock(video);
            if (row.isEmpty() || row.get().getResultDeletedAt() != null || row.get().getResultCleanupToken() != null)
                return Optional.empty();
            if (repository.renew(video, token, repository.now().plus(lease)) != 1) return Optional.empty();
            return Optional.of(repository.leaseUntil(video, token));
        });
    }
    public void release(UUID video, UUID token) {
        Objects.requireNonNull(video); Objects.requireNonNull(token);
        committed(() -> { repository.lock(video); repository.release(video, token); return null; });
    }
    public List<Cleanup> claimExpired(int limit, Duration lease) {
        validate(lease);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Cleanup batch must be 1..100");
        return committed(() -> {
            var claims = new ArrayList<Cleanup>();
            for (var row : repository.lockExpired(limit)) {
                var artifact = mapper.artifact(row);
                UUID token = UUID.randomUUID(); Instant until = repository.now().plus(lease);
                changed(repository.claim(row.getId(), token, until));
                repository.prune(row.getId());
                claims.add(new Cleanup(token, artifact, until));
            }
            return List.copyOf(claims);
        });
    }
    public boolean deleted(UUID video, UUID token) {
        Objects.requireNonNull(video); Objects.requireNonNull(token);
        return committed(() -> repository.deleted(video, token) == 1);
    }
    public boolean retryCleanup(UUID video, UUID token, Duration delay) {
        Objects.requireNonNull(video); Objects.requireNonNull(token); validate(delay);
        return committed(() -> repository.retry(video, token, repository.now().plus(delay)) == 1);
    }
    private static void validate(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero() || duration.getNano() != 0 || duration.getSeconds() > 86400)
            throw new IllegalArgumentException("Duration must be whole seconds in 1..86400");
    }
    private static void changed(int count) { if (count != 1) throw new DownloadException(DownloadException.Reason.UNAVAILABLE); }
    private <T> T committed(Supplier<T> action) {
        try { return transactions.execute(status -> action.get()); }
        catch (DataAccessException | TransactionException failure) { throw new DownloadException(DownloadException.Reason.UNAVAILABLE, failure); }
    }
}
