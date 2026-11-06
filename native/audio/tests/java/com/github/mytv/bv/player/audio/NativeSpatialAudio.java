package com.github.mytv.bv.player.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class NativeSpatialAudio {
    private static native int nativeApiVersion();
    private static native int nativeUpmix51(ByteBuffer input, int offset, int length,
        ByteBuffer output, int outputOffset, int encoding, double surround, double startPan, double endPan);

    static void test() {
        if (nativeApiVersion() != 1) throw new AssertionError("spatial ABI");
        ByteBuffer input = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output = ByteBuffer.allocateDirect(28).order(ByteOrder.LITTLE_ENDIAN);
        input.putFloat(0,0.25f).putFloat(4,0.25f);
        int frames = nativeUpmix51(input,0,8,output,2,2,0.35,0.0,0.0);
        if (frames != 1 || output.getFloat(10) != 0.125f || output.getFloat(14) != 0.0f ||
            output.getFloat(18) != 0.0f || output.getFloat(22) != 0.0f) throw new AssertionError("5.1 bus");
        if (input.position()!=0 || output.position()!=0) throw new AssertionError("positions");
        if (nativeUpmix51(input,0,8,output,8,2,0.35,0,0) != -1) throw new AssertionError("output bound");
        if (nativeUpmix51(input,0,7,output,0,2,0.35,0,0) != -1) throw new AssertionError("partial frame");
        if (nativeUpmix51(input,0,8,output,0,2,Double.NaN,0,0) != -1) throw new AssertionError("invalid parameters");
    }
}
