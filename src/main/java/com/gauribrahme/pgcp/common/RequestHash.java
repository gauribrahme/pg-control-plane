package com.gauribrahme.pgcp.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RequestHash {
    private RequestHash() {}

    /** SHA-256 over the normalised request fields (after defaults are applied). */
    public static String of(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            sb.append(p).append("|");
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
