package br.com.fiap.fiapx.video.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import br.com.fiap.fiapx.video.domain.Video;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcVideoRepositoryTest {
    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private ResultSet resultSet;
    @Captor private ArgumentCaptor<MapSqlParameterSource> parameters;
    private JdbcVideoRepository repository;
    private final Video video = new Video(UUID.randomUUID(), UUID.randomUUID(), "sample.mp4", "owners/original",
            100_000_000, Instant.parse("2026-09-22T12:00:00Z"));

    @BeforeEach
    void setUp() {
        repository = new JdbcVideoRepository(jdbc);
    }

    @Test
    void insertsInitialMetadataWithoutClaimingProcessingAcceptance() {
        repository.insert(video);
        verify(jdbc).update(contains("INSERT INTO videos"), parameters.capture());
        var values = parameters.getValue();
        assertAll(() -> assertEquals(video.id(), values.getValue("id")),
                () -> assertEquals(video.ownerId(), values.getValue("ownerId")),
                () -> assertEquals(video.originalName(), values.getValue("originalName")),
                () -> assertEquals(video.originalObjectKey(), values.getValue("originalObjectKey")),
                () -> assertEquals(video.sizeBytes(), values.getValue("sizeBytes")),
                () -> assertEquals("UPLOADING", values.getValue("status")),
                () -> assertEquals(Timestamp.from(video.createdAt()), values.getValue("createdAt")));
    }

    @Test
    void mapsOwnedVideoWithUtcTimestamp() throws SQLException {
        when(resultSet.getObject("id", UUID.class)).thenReturn(video.id());
        when(resultSet.getObject("owner_id", UUID.class)).thenReturn(video.ownerId());
        when(resultSet.getString("original_name")).thenReturn(video.originalName());
        when(resultSet.getString("original_object_key")).thenReturn(video.originalObjectKey());
        when(resultSet.getLong("size_bytes")).thenReturn(video.sizeBytes());
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(video.createdAt()));
        when(jdbc.query(contains("id = :id AND owner_id = :ownerId"),
                eq(Map.of("id", video.id(), "ownerId", video.ownerId())), anyRowMapper()))
                .thenAnswer(invocation -> {
                    RowMapper<Video> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        assertEquals(video, repository.findByIdAndOwnerId(video.id(), video.ownerId()).orElseThrow());
    }

    @Test
    void returnsEmptyWhenNoOwnedRecordIsFound() {
        UUID anotherOwner = UUID.randomUUID();
        when(jdbc.query(anyString(), eq(Map.of("id", video.id(), "ownerId", anotherOwner)), anyRowMapper()))
                .thenReturn(List.of());
        assertTrue(repository.findByIdAndOwnerId(video.id(), anotherOwner).isEmpty());
    }

    @Test
    void propagatesDuplicateRatherThanOverwritingExistingVideo() {
        var failure = new DuplicateKeyException("duplicate");
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DuplicateKeyException.class, () -> repository.insert(video)));
    }

    @Test
    void propagatesDatabaseFailureRatherThanReportingNotFound() {
        var failure = new DataAccessResourceFailureException("unavailable");
        when(jdbc.query(anyString(), anyMap(), anyRowMapper())).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> repository.findByIdAndOwnerId(video.id(), video.ownerId())));
    }

    @Test
    void refusesMissingArgumentsBeforeCallingDatabase() {
        assertAll(() -> assertThrows(NullPointerException.class, () -> repository.insert(null)),
                () -> assertThrows(NullPointerException.class, () -> repository.findByIdAndOwnerId(null, video.ownerId())),
                () -> assertThrows(NullPointerException.class, () -> repository.findByIdAndOwnerId(video.id(), null)),
                () -> assertThrows(NullPointerException.class, () -> new JdbcVideoRepository(null)));
        verifyNoInteractions(jdbc);
    }

    private static RowMapper<Video> anyRowMapper() {
        return any();
    }
}
