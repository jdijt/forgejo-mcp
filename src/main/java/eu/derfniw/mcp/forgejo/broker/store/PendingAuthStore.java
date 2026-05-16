package eu.derfniw.mcp.forgejo.broker.store;

import eu.derfniw.mcp.forgejo.broker.model.PendingAuth;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Optional;

@ApplicationScoped
public class PendingAuthStore {

    private static final String PREFIX = "broker:pending_auth:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final ValueCommands<String, PendingAuth> cmds;

    public PendingAuthStore(RedisDataSource redis) {
        this.cmds = redis.value(PendingAuth.class);
    }

    public void put(String forgejoState, PendingAuth value) {
        cmds.setex(key(forgejoState), TTL.toSeconds(), value);
    }

    public Optional<PendingAuth> consume(String forgejoState) {
        return Optional.ofNullable(cmds.getdel(key(forgejoState)));
    }

    private static String key(String forgejoState) {
        return PREFIX + forgejoState;
    }
}
