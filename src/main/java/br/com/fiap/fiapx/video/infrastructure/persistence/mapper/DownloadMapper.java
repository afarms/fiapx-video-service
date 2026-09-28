package br.com.fiap.fiapx.video.infrastructure.persistence.mapper;

import br.com.fiap.fiapx.video.core.domain.DownloadArtifact;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringDownloadRepository.ResultRow;

public final class DownloadMapper {
    public DownloadArtifact artifact(ResultRow row) {
        return new DownloadArtifact(row.getId(), row.getOwnerId(), row.getResultBucket(), row.getResultObjectKey(),
                row.getResultSizeBytes(), row.getResultSha256());
    }
}
