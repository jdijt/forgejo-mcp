package eu.derfniw.mcp.forgejo.broker.crypto;

public enum TokenType {
    ACCESS_TOKEN("mcp_at_"),
    REFRESH_TOKEN("mcp_rt_"),
    AUTH_CODE("mcp_ac_"),
    PENDING_AUTH("mcp_pa_");

    private final String prefix;

    TokenType(String prefix) {
        this.prefix = prefix;
    }

    public String getPrefix() {
        return prefix;
    }
}
