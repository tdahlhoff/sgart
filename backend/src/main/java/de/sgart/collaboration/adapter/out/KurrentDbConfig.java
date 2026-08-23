package de.sgart.collaboration.adapter.out;

import de.sgart.shared.EventStore;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the real KurrentDB {@link EventStore} adapter (Story 1.6). Constructing the client parses
 * the connection string and builds a gRPC channel that connects lazily on first RPC — like the
 * {@code JwtDecoder} bean (Story 1.4's {@code SecurityConfig}), this bean performs no I/O at
 * context startup, so {@code contextLoads()} survives KurrentDB being down (see {@code
 * ContextLoadsWithoutKurrentDbTest}).
 */
@Configuration
public class KurrentDbConfig {

    @Bean
    KurrentDBClient kurrentDbClient(@Value("${sgart.kurrentdb.connection-string}") String connectionString) {
        return KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow(connectionString));
    }

    @Bean
    EventStore eventStore(KurrentDBClient kurrentDbClient) {
        return new KurrentDbEventStore(kurrentDbClient);
    }
}
