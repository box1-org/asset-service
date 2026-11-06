package org.box1.asset.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** RFC 9457 problem details. {@code code} is a stable machine-readable reason. */
public record Problem(String type, String title, int status, String detail, String code) {
    public static final String MEDIA_TYPE = "application/problem+json";

    public static Response response(int status, String title, String code, String detail) {
        return Response.status(status).type(MediaType.valueOf(MEDIA_TYPE))
                .entity(new Problem("https://box1.org/problems/" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'), title,
                        status, detail, code))
                .build();
    }
}
