package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.DevicePlatform;
import de.sgart.identity.domain.DeviceToken;
import de.sgart.identity.domain.DeviceTokenRepository;
import de.sgart.identity.domain.KeycloakUserId;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Durable PostgreSQL {@link DeviceTokenRepository} (Story 4.5, AC5). Plain SQL over {@link
 * JdbcClient} — mirrors {@link JdbcMemberMappingRepository}. Schema: {@code
 * db/migration/V17__device_token.sql}.
 */
public final class JdbcDeviceTokenRepository implements DeviceTokenRepository {

    private final JdbcClient jdbcClient;

    public JdbcDeviceTokenRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public void upsert(DeviceToken deviceToken) {
        jdbcClient
                .sql("""
                        INSERT INTO device_token (token, keycloak_user_id, platform, registered_at)
                        VALUES (:token, :keycloakUserId, :platform, :registeredAt)
                        ON CONFLICT (token) DO UPDATE SET
                            keycloak_user_id = EXCLUDED.keycloak_user_id,
                            platform = EXCLUDED.platform,
                            registered_at = EXCLUDED.registered_at
                        """)
                .param("token", deviceToken.token())
                .param("keycloakUserId", deviceToken.keycloakUserId().value())
                .param("platform", deviceToken.platform().name())
                .param("registeredAt", Timestamp.from(deviceToken.registeredAt()))
                .update();
    }

    @Override
    public Optional<DeviceToken> findByToken(String token) {
        return jdbcClient
                .sql("SELECT token, keycloak_user_id, platform, registered_at FROM device_token WHERE token = :token")
                .param("token", token)
                .query(JdbcDeviceTokenRepository::mapRow)
                .optional();
    }

    @Override
    public List<DeviceToken> findByKeycloakUserId(KeycloakUserId keycloakUserId) {
        return jdbcClient
                .sql("""
                        SELECT token, keycloak_user_id, platform, registered_at FROM device_token
                        WHERE keycloak_user_id = :keycloakUserId
                        """)
                .param("keycloakUserId", keycloakUserId.value())
                .query(JdbcDeviceTokenRepository::mapRow)
                .list();
    }

    @Override
    public void deleteByToken(String token) {
        jdbcClient.sql("DELETE FROM device_token WHERE token = :token").param("token", token).update();
    }

    @Override
    public void deleteAllForUser(KeycloakUserId keycloakUserId) {
        jdbcClient
                .sql("DELETE FROM device_token WHERE keycloak_user_id = :keycloakUserId")
                .param("keycloakUserId", keycloakUserId.value())
                .update();
    }

    private static DeviceToken mapRow(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new DeviceToken(
                new KeycloakUserId(resultSet.getString("keycloak_user_id")),
                resultSet.getString("token"),
                DevicePlatform.valueOf(resultSet.getString("platform")),
                resultSet.getTimestamp("registered_at").toInstant());
    }
}
