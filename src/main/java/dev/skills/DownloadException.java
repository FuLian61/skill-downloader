package dev.skills;

import java.io.IOException;

public final class DownloadException extends IOException {
    public enum Code { INVALID_INPUT, AUTH_REQUIRED, FORBIDDEN, NOT_FOUND, RATE_LIMITED, REMOTE_ERROR, INVALID_RESPONSE, SOURCE_UNAVAILABLE }

    private final Code code;
    private final int status;

    public DownloadException(Code code, String message) {
        this(code, 0, message);
    }

    public DownloadException(Code code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public Code code() { return code; }
    public int status() { return status; }

    static DownloadException http(int status, String host) {
        Code code = switch (status) {
            case 401 -> Code.AUTH_REQUIRED;
            case 403 -> Code.FORBIDDEN;
            case 404 -> Code.NOT_FOUND;
            case 429 -> Code.RATE_LIMITED;
            default -> Code.REMOTE_ERROR;
        };
        return new DownloadException(code, status, "HTTP " + status + "，来源 " + host);
    }
}
