package com.github.mytv.bv.player.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class NativeAdaptiveUpmix {
    private static native int nativeApiVersion();
    private static native long nativeCreate(int rate, double centre, double surround, double decorrelation, double trim);
    private static native boolean nativeReset(long handle);
    private static native void nativeClose(long handle);
    private static native int nativeProcess(long handle, ByteBuffer input, int offset, int length,
        ByteBuffer output, int outputOffset, int encoding);

    static void test() {
        check(nativeApiVersion() == 1, "adaptive ABI");
        check(nativeCreate(0, .45, .30, .65, -3) == 0, "rate validation");
        check(nativeCreate(48000, Double.NaN, .30, .65, -3) == 0, "settings validation");
        long handle = nativeCreate(48000, .45, .30, .65, -3);
        check(handle != 0, "create");
        ByteBuffer input = ByteBuffer.allocateDirect(34).order(ByteOrder.LITTLE_ENDIAN);
        input.putFloat(1, .2f).putFloat(5, .2f);
        ByteBuffer output = ByteBuffer.allocateDirect(25).order(ByteOrder.LITTLE_ENDIAN);
        try {
            check(nativeProcess(handle, input, 1, 8, output, 1, 2) == 1, "unaligned direct buffers");
            check(output.getFloat(1) > .1f && output.getFloat(13) == 0 && output.getFloat(17) == 0
                && output.getFloat(21) == 0, "mono and channel order");
            check(input.position() == 0 && output.position() == 0, "Java positions remain owned by caller");
            check(nativeProcess(handle, input, 0, 8, output, 2, 2) == -1, "output capacity");
            check(nativeProcess(handle, input, 0, 7, output, 0, 2) == -1, "partial frame");
            check(nativeProcess(handle, input, 0, 8, input.duplicate(), 1, 2) == -1, "overlapping views");
            check(nativeProcess(handle, input, -1, 8, output, 0, 2) == -1, "negative offset");
            check(nativeReset(handle), "reset");
        } finally { nativeClose(handle); }
        check(!nativeReset(handle), "stale reset rejected");
        check(nativeProcess(handle, input, 0, 8, output, 0, 2) == -1, "stale handle rejected");
        nativeClose(handle);
        long fresh = nativeCreate(48000, .45, .30, .65, -3);
        check(fresh != 0 && fresh != handle, "handle IDs are not reused");
        nativeClose(fresh);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
