// Source: https://github.com/sdf123098/Sparkle-Morpher
// Branch: fa26.2 @ 816235f2
// Copyright (c) 2026 Marciken / MIT License

package me.zuogeren.slimefunhelperplus.module.custommodel.format.zstd;

public class MalformedInputException extends RuntimeException {
    private final long offset;

    public MalformedInputException(long offset) {
        this(offset, "Malformed input");
    }

    public MalformedInputException(long offset, String reason) {
        super(reason + ": offset=" + offset);
        this.offset = offset;
    }

    public long getOffset() {
        return offset;
    }
}

