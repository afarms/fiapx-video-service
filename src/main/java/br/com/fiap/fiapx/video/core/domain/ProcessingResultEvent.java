package br.com.fiap.fiapx.video.core.domain;

import java.time.Instant;
import java.util.*;

/** Validated result envelope. Storage references remain private to persistence. */
public record ProcessingResultEvent(UUID eventId, UUID videoId, UUID ownerId, UUID correlationId,
        Instant occurredAt, Type type, UUID jobId, UUID attemptId, long attempt, long version,
        Instant startedAt, Instant completedAt, Instant expiresAt, Instant failedAt,
        String failureCode, ResultReference result) {
    public enum Type { ProcessingStarted, ProcessingCompleted, ProcessingFailed }
    public record ResultReference(String bucket, String objectKey, long sizeBytes, String sha256, long frameCount) {}
    private static final Set<String> FAILURES=Set.of("INVALID_MEDIA","DURATION_EXCEEDED","OUTPUT_LIMIT_EXCEEDED","PROCESSING_TIMEOUT","PROCESSING_FAILED");
    public ProcessingResultEvent {
        Objects.requireNonNull(eventId); Objects.requireNonNull(videoId); Objects.requireNonNull(ownerId);
        Objects.requireNonNull(correlationId); Objects.requireNonNull(occurredAt); Objects.requireNonNull(type);
        Objects.requireNonNull(attemptId);
        require(videoId.equals(jobId) && attempt>0 && attempt<=Integer.MAX_VALUE && version>0,"Invalid job identity/version");
        switch (type) {
            case ProcessingStarted -> require(occurredAt.equals(startedAt) && completedAt==null && expiresAt==null
                    && failedAt==null && failureCode==null && result==null,"Invalid start");
            case ProcessingFailed -> require(occurredAt.equals(failedAt) && startedAt==null && completedAt==null
                    && expiresAt==null && result==null && failureCode!=null && FAILURES.contains(failureCode),"Invalid failure");
            case ProcessingCompleted -> {
                require(occurredAt.equals(completedAt) && occurredAt.plusSeconds(86400).equals(expiresAt)
                        && startedAt==null && failedAt==null && failureCode==null && result!=null,"Invalid completion");
                require(result.bucket()!=null && result.bucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"),"Invalid result bucket");
                String prefix="results/"+ownerId+"/"+videoId+"/";
                require(result.objectKey()!=null && result.objectKey().startsWith(prefix),"Invalid result owner/video");
                // Recovery can confirm an object produced by an earlier attempt: do not require attemptId here.
                require(result.objectKey().substring(prefix.length()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/frames\\.zip"),"Invalid result key");
                require(result.sizeBytes()>0 && result.sizeBytes()<=1073741824L && result.frameCount()>0
                        && result.frameCount()<=Integer.MAX_VALUE && result.sha256()!=null && result.sha256().matches("[0-9a-f]{64}"),"Invalid result metadata");
            }
        }
    }
    /** Duplicate identity is checked in the inbox before applying this transition policy. */
    public boolean shouldApply(Video video,long currentVersion) {
        require(video.id().equals(videoId) && video.ownerId().equals(ownerId),"Conflicting video owner");
        require(video.state()!=VideoStatus.UPLOADING,"Video has not been accepted");
        require(version!=currentVersion,"Conflicting event version");
        if (video.state().terminal()) {
            require(type==Type.ProcessingStarted,"Conflicting terminal result");
            return false;
        }
        return version>currentVersion;
    }
    public VideoStatus status() {
        return switch (type) { case ProcessingStarted -> VideoStatus.PROCESSING; case ProcessingCompleted -> VideoStatus.COMPLETED; case ProcessingFailed -> VideoStatus.FAILED; };
    }
    private static void require(boolean valid,String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
