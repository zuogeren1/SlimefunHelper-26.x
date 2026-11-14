// Source: https://github.com/sdf123098/Sparkle-Morpher (common/src/main/java/com/micaftic/morpher/util/DigestUtil.java)
// Branch/commit: fa26.2 @ 816235f2
// Copyright (c) 2026 Marciken / MIT
package me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 摘要工具：只保留几何解析需要的 SHA-256 十六进制串。 */
public final class DigestUtil {

    private DigestUtil() {}

    public static String sha256Hex(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
