package com.github.mytv.bv.player.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/** Runs the real JNI ABI on a desktop JVM, without an Android SDK or emulator. */
public final class NativePcmGain {
    private static native int nativeApiVersion();
    private static native double nativeProcess(
        ByteBuffer input, int inputOffset, int length,
        ByteBuffer output, int outputOffset, int encoding, int channels,
        double current, double target, double step
    );

    public static void main(String[] args) {
        System.loadLibrary("mybv_audio");
        check(nativeApiVersion() == 1, "ABI version");
        checkOffsetsAndPartialFrames();
        checkInvalidRangesAndAliases();
        checkEveryPcm16Value();
        checkFloatAndRampParity();
        NativeSpatialAudio.test();
        NativeAdaptiveUpmix.test();
        System.out.println("JNI smoke tests passed: offsets, bounds, aliases, PCM16, float, gain ramps, matrix and adaptive 5.1 lifecycle");
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void checkOffsetsAndPartialFrames() {
        ByteBuffer input = buffer(16);
        ByteBuffer output = buffer(20);
        for (int i = 0; i < output.capacity(); i++) output.put(i, (byte) 0x55);
        input.putShort(3, (short) 1000).putShort(5, (short) -1000).put(7, (byte) 0x7f);
        input.position(3).limit(8);
        output.position(5).limit(10);
        double gain = nativeProcess(input, 3, 5, output, 5, 1, 2, 1.0, 2.0, 0.5);
        check(gain == 1.5, "unaligned gain");
        check(output.getShort(5) == 1500 && output.getShort(7) == -1500, "offset samples");
        check(output.get(9) == (byte) 0x7f, "partial frame survives");
        check(input.position() == 3 && output.position() == 5, "JNI leaves positions to Kotlin");
        output.clear();
        check(output.get(4) == 0x55 && output.get(10) == 0x55, "sentinels intact");
    }

    private static void checkInvalidRangesAndAliases() {
        ByteBuffer input = buffer(16);
        ByteBuffer output = buffer(16);
        for (int i = 0; i < output.capacity(); i++) output.put(i, (byte) 0x55);
        int[][] badRanges = {{-1, 4, 0}, {0, -1, 0}, {15, 4, 0}, {0, 4, 15}, {0, 4, -1}, {Integer.MAX_VALUE, 4, 0}};
        for (int[] range : badRanges) {
            double result = nativeProcess(input, range[0], range[1], output, range[2], 1, 2, 1, 2, 0.5);
            check(Double.isNaN(result), "bad range rejected");
        }
        check(Double.isNaN(nativeProcess(input, 0, 8, input.duplicate(), 0, 1, 2, 1, 2, 0.5)), "aliased buffers rejected");
        check(Double.isNaN(nativeProcess(ByteBuffer.allocate(16), 0, 8, output, 0, 1, 2, 1, 2, 0.5)), "heap input rejected");
        check(Double.isNaN(nativeProcess(input, 0, 8, output, 0, 1, 0, 1, 2, 0.5)), "zero channels rejected");
        check(Double.isNaN(nativeProcess(input, 0, 8, output, 0, 99, 2, 1, 2, 0.5)), "bad encoding rejected");
        check(Double.isNaN(nativeProcess(input, 0, 8, output, 0, 1, 2, Double.NaN, 2, 0.5)), "bad gain rejected");
        for (int i = 0; i < output.capacity(); i++) check(output.get(i) == 0x55, "failed call did not write");
    }

    private static void checkEveryPcm16Value() {
        int samples = 65536;
        ByteBuffer input = buffer(samples * 2);
        ByteBuffer output = buffer(samples * 2);
        for (int i = 0; i < samples; i++) input.putShort(i * 2, (short) (i + Short.MIN_VALUE));
        for (double gain : new double[] {0.001, 0.5, 0.99999, 1.0, 1.2, 3.9810717055349722}) {
            check(nativeProcess(input, 0, input.capacity(), output, 0, 1, 8, gain, gain, 0) == gain, "constant gain state");
            for (int i = 0; i < samples; i++) {
                double scaled = (i + Short.MIN_VALUE) * gain;
                short expected = (short) (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
                check(output.getShort(i * 2) == expected, "PCM16 parity: " + i + " gain=" + gain);
            }
        }
    }

    private static void checkFloatAndRampParity() {
        Random random = new Random(0x42564c);
        for (int channels : new int[] {1, 2, 6, 8}) {
            int frames = 997;
            ByteBuffer input = buffer(frames * channels * 4);
            ByteBuffer output = buffer(input.capacity());
            for (int i = 0; i < frames * channels; i++) input.putFloat(i * 4, (random.nextFloat() - 0.5f) * 3.0f);
            double gain = 0.25;
            double target = 2.0;
            double step = 0.003;
            double result = nativeProcess(input.asReadOnlyBuffer(), 0, input.capacity(), output, 0, 2, channels, gain, target, step);
            for (int frame = 0; frame < frames; frame++) {
                gain = Math.min(target, gain + step);
                for (int channel = 0; channel < channels; channel++) {
                    int offset = (frame * channels + channel) * 4;
                    float expected = (float) Math.max(-1.0, Math.min(1.0, input.getFloat(offset) * gain));
                    check(Float.floatToRawIntBits(output.getFloat(offset)) == Float.floatToRawIntBits(expected), "float ramp parity");
                }
            }
            check(result == gain, "final ramp state");
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
