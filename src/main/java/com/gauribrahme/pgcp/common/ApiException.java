package com.gauribrahme.pgcp.common;

import org.springframework.http.HttpStatus;

/** Domain error that maps 1:1 to an RFC 7807 problem response. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ApiException notFound(String what, String id) {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", what + " " + id + " not found");
    }
    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }
    public static ApiException unprocessable(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, detail);
    }
}
