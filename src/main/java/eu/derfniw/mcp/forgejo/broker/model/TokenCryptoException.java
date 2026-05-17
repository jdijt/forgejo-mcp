package eu.derfniw.mcp.forgejo.broker.model;

public final class TokenCryptoException extends BadRequest {
    public TokenCryptoException(String message) {
        super(message);
    }

    public TokenCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
