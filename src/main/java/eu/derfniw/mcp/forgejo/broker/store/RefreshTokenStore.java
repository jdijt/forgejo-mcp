package eu.derfniw.mcp.forgejo.broker.store;

import eu.derfniw.mcp.forgejo.broker.model.RefreshTokenEntry;
import eu.derfniw.mcp.forgejo.config.BrokerConfig;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class RefreshTokenStore {

    private static final String PREFIX = "broker:refresh_token:";

    private final ValueCommands<String, RefreshTokenEntry> cmds;
    private final BrokerConfig brokerConfig;

    public RefreshTokenStore(RedisDataSource redis, BrokerConfig brokerConfig) {
        this.cmds = redis.value(RefreshTokenEntry.class);
        this.brokerConfig = brokerConfig;
    }

    public void put(String token, RefreshTokenEntry value) {
        cmds.setex(key(token), brokerConfig.refreshTokenTtl().toSeconds(), value);
    }

    /** Single-use: rotated on every refresh grant. */
    public Optional<RefreshTokenEntry> consume(String token) {
        return Optional.ofNullable(cmds.getdel(key(token)));
    }

    private static String key(String token) {
        return PREFIX + token;
    }
}
