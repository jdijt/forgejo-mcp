package eu.derfniw.mcp.forgejo.broker.store;

import eu.derfniw.mcp.forgejo.broker.model.AuthCodeEntry;
import eu.derfniw.mcp.forgejo.config.BrokerConfig;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class AuthCodeStore {

    private static final String PREFIX = "broker:auth_code:";

    private final ValueCommands<String, AuthCodeEntry> cmds;
    private final BrokerConfig brokerConfig;

    public AuthCodeStore(RedisDataSource redis, BrokerConfig brokerConfig) {
        this.cmds = redis.value(AuthCodeEntry.class);
        this.brokerConfig = brokerConfig;
    }

    public void put(String code, AuthCodeEntry value) {
        cmds.setex(key(code), brokerConfig.authCodeTtl().toSeconds(), value);
    }

    public Optional<AuthCodeEntry> consume(String code) {
        return Optional.ofNullable(cmds.getdel(key(code)));
    }

    private static String key(String code) {
        return PREFIX + code;
    }
}
