package org.box1.asset.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Opaque keyset cursors: base64url of "epochSecond.nano|key". */
final class Cursors {
    private Cursors() {
    }

    static String encode(Instant time, String key) {
        String raw = time.getEpochSecond() + "." + time.getNano() + "|" + key;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Position decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            String[] time = raw.substring(0, bar).split("\\.");
            Instant instant = Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1]));
            String key = raw.substring(bar + 1);
            if (key.isEmpty() || key.length() > 80) {
                throw new IllegalArgumentException();
            }
            return new Position(instant, key);
        } catch (RuntimeException error) {
            throw new InvalidRequestException("INVALID_CURSOR", "cursor is not valid");
        }
    }

    record Position(Instant time, String key) {
    }
}
