package com.github.mytv.bv.player.audio

import java.nio.ByteBuffer

/** Optional six-channel bed encoder. It never labels its output JOC/Atmos. */
internal object NativeEac3 {
    val available: Boolean by lazy {
        try { System.loadLibrary("mybv_encode"); nativeAvailable() }
        catch (_: UnsatisfiedLinkError) { false } catch (_: SecurityException) { false }
    }
    fun create(rate: Int,bitrate: Int=640000): Session? {
        if (!available || rate !in 8000..192000) return null
        val handle=nativeCreate(rate,bitrate)
        return if (handle==0L) null else Session(handle)
    }
    class Session internal constructor(private var handle: Long) : AutoCloseable {
        val delayOutputFrames: Int get()=if(handle==0L) 0 else nativeDelay(handle)
        fun push(input: ByteBuffer,maxFrames: Int=1024): Int {
            if (handle==0L || !input.isDirect) return -22
            val frames=minOf(maxFrames,1024,input.remaining()/12)
            if (frames<=0) return 0
            val accepted=nativePush(handle,input,input.position(),frames)
            if (accepted>0) input.position(input.position()+accepted*12)
            return accepted
        }
        fun read(output: ByteBuffer): Int {
            if (handle==0L || !output.isDirect || output.isReadOnly || output.remaining()>32768) return -22
            val bytes=nativeRead(handle,output,output.position(),output.remaining())
            if (bytes>0) output.position(output.position()+bytes)
            return bytes
        }
        fun finish() { if (handle!=0L) nativeFinish(handle) }
        override fun close() { if (handle!=0L) { nativeClose(handle); handle=0 } }
    }
    @JvmStatic private external fun nativeAvailable(): Boolean
    @JvmStatic private external fun nativeCreate(rate: Int,bitrate: Int): Long
    @JvmStatic private external fun nativeClose(handle: Long)
    @JvmStatic private external fun nativeFinish(handle: Long)
    @JvmStatic private external fun nativeDelay(handle: Long): Int
    @JvmStatic private external fun nativePush(handle: Long,input: ByteBuffer,offset: Int,frames: Int): Int
    @JvmStatic private external fun nativeRead(handle: Long,output: ByteBuffer,offset: Int,length: Int): Int
}
