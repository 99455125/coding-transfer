package dev.codingtransfer.core;

/** Shared clipboard chunk limits for project and single-file transfers. */
public final class TransferLimits {
    private TransferLimits() {}

    public static final int MAX_CHUNK_KB = 1024 * 1024;
    public static final int MAX_DATA_CHARS = MAX_CHUNK_KB * 1024;
    public static final int MAX_PACKET_CHARS = MAX_DATA_CHARS + 4096;
    public static final int MAX_FILE_CHUNK_BYTES = MAX_DATA_CHARS / 4 * 3;
}
