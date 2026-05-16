package eu.derfniw.mcp.forgejo.broker.store;

import eu.derfniw.mcp.forgejo.broker.model.AccessTokenEntry;
import eu.derfniw.mcp.forgejo.config.BrokerConfig;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class AccessTokenStore {

    private static final String PREFIX = "broker:access_token:";

    private final ValueCommands<String, AccessTokenEntry> cmds;
    private final BrokerConfig brokerConfig;

    public AccessTokenStore(RedisDataSource redis, BrokerConfig brokerConfig) {
        this.cmds = redis.value(AccessTokenEntry.class);
        this.brokerConfig = brokerConfig;
    }

    public void put(String token, AccessTokenEntry value) {
        cmds.setex(key(token), brokerConfig.accessTokenTtl().toSeconds(), value);
    }

    public Optional<AccessTokenEntry> get(String token) {
        return Optional.ofNullable(cmds.get(key(token)));
    }

    /**
     * Update the entry while preserving the existing TTL — used after silently
     * refreshing the upstream Forgejo token.
     */
    public void replacePreservingTtl(String token, AccessTokenEntry value) {
        cmds.set(key(token), value, new SetArgs().keepttl());
    }

    public void delete(String token) {
        cmds.getdel(key(token));
    }

    private static String key(String token) {
        return PREFIX + token;
    }
}
