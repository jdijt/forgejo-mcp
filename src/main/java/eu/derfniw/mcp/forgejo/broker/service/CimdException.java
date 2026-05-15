package eu.derfniw.mcp.forgejo.broker.service;

public class CimdException extends RuntimeException {
    public CimdException(String message) { super(message); }
    public CimdException(String message, Throwable cause) { super(message, cause); }
}
