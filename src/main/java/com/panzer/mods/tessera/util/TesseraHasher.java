package com.panzer.mods.tessera.util;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content hash for disk-cache keys: SHA-256 via {@link MessageDigest}, built
 * into the JDK and intrinsified on every supported platform. Only used to key
 * {@code AtlasCache} entries, not for anything security-sensitive.
 */
public final class TesseraHasher {

    private TesseraHasher() {
    }

    public static String hashContent(ByteBuffer content) {
        MessageDigest digest = newDigest();
        ByteBuffer duplicate = content.duplicate();
        digest.update(duplicate);
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String hashContent(byte[] content) {
        MessageDigest digest = newDigest();
        digest.update(content);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JDK distribution ships SHA-256 -- this is unreachable
            // in practice, but MessageDigest.getInstance's checked
            // exception has to go somewhere.
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}
