package br.com.fiap.fiapx.video.infrastructure.persistence;

import br.com.fiap.fiapx.video.domain.Video;
import br.com.fiap.fiapx.video.domain.VideoRepository;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVideoRepository implements VideoRepository {
    private static final String INSERT = """
            INSERT INTO videos (id, owner_id, original_name, original_object_key, size_bytes, status, created_at)
            VALUES (:id, :ownerId, :originalName, :originalObjectKey, :sizeBytes, :status, :createdAt)
            """;
    private static final String FIND_OWNED = """
            SELECT id, owner_id, original_name, original_object_key, size_bytes, created_at
            FROM videos WHERE id = :id AND owner_id = :ownerId
            """;
    private static final RowMapper<Video> MAPPER = (rs, rowNum) -> new Video(
            rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
            rs.getString("original_name"), rs.getString("original_object_key"),
            rs.getLong("size_bytes"), rs.getTimestamp("created_at").toInstant());
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcVideoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(Video video) {
        Objects.requireNonNull(video, "video is required");
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("id", video.id())
                .addValue("ownerId", video.ownerId())
                .addValue("originalName", video.originalName())
                .addValue("originalObjectKey", video.originalObjectKey())
                .addValue("sizeBytes", video.sizeBytes())
                .addValue("status", video.status())
                .addValue("createdAt", Timestamp.from(video.createdAt())));
    }

    @Override
    public Optional<Video> findByIdAndOwnerId(UUID id, UUID ownerId) {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(ownerId, "ownerId is required");
        return jdbc.query(FIND_OWNED, Map.of("id", id, "ownerId", ownerId), MAPPER).stream().findFirst();
    }
}
