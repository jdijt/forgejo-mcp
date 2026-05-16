package eu.derfniw.mcp.forgejo.broker.model;

import java.security.SecureRandom;
import java.util.Base64;

public final class TokenIds {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final int BYTES = 32;

    private TokenIds() {}

    public static String mcpAccessToken() {
        return "mcp_at_" + random();
    }

    public static String mcpRefreshToken() {
        return "mcp_rt_" + random();
    }

    public static String mcpAuthCode() {
        return "mcp_ac_" + random();
    }

    public static String forgejoState() {
        return random();
    }

    private static String random() {
        byte[] buf = new byte[BYTES];
        RNG.nextBytes(buf);
        return B64.encodeToString(buf);
    }
}
