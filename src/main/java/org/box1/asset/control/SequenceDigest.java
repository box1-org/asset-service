package org.box1.asset.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** SHA-256 over the canonical lines of a run's first {@link #LENGTH} emissions (D-005). */
public final class SequenceDigest {
    public static final int LENGTH = 200;
    private final MessageDigest digest;
    private final Instant start;
    private int count;
    private String value;

    public SequenceDigest(Instant start) {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        this.start = start;
    }

    public void add(Emission emission) {
        if (value != null) {
            return;
        }
        digest.update(emission.canonical(start).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        if (++count == LENGTH) {
            finish();
        }
    }

    /** Null until {@link #LENGTH} emissions or {@link #finish()}. */
    public String value() {
        return value;
    }

    public String finish() {
        if (value == null) {
            value = count + ":" + HexFormat.of().formatHex(digest.digest());
        }
        return value;
    }

    public static String of(List<Emission> emissions, Instant start) {
        SequenceDigest sequence = new SequenceDigest(start);
        emissions.forEach(sequence::add);
        return sequence.finish();
    }
}
