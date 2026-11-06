/* MIT-licensed ABI glue; links to separately built LGPL FFmpeg libraries.
 * No Dolby Atmos/JOC object encoding or proprietary SDK is represented here. */
#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <libavcodec/avcodec.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>

typedef struct {
    AVCodecContext *codec;
    AVFrame *frame;
    AVPacket *packet;
    SwrContext *resampler;
    AVAudioFifo *fifo;
    uint8_t **converted;
    int converted_capacity;
    int pending_frame;
    int pending_packet;
    int finishing;
    int resampler_flushed;
    int tail_added;
    int eos_sent;
    int output_ended;
    int64_t next_pts;
} MybvEncoder;

void mybv_encoder_free(void *opaque) {
    MybvEncoder *e=opaque;
    if (!e) return;
    if (e->converted) { av_freep(&e->converted[0]); av_freep(&e->converted); }
    av_audio_fifo_free(e->fifo);
    swr_free(&e->resampler);
    av_packet_free(&e->packet);
    av_frame_free(&e->frame);
    avcodec_free_context(&e->codec);
    av_free(e);
}

void *mybv_encoder_create(int sample_rate,int bitrate) {
    if (sample_rate<8000 || sample_rate>192000 || bitrate<192000 || bitrate>1024000) return NULL;
    const AVCodec *codec=avcodec_find_encoder(AV_CODEC_ID_EAC3);
    if (!codec) return NULL;
    MybvEncoder *e=av_mallocz(sizeof(*e));
    if (!e) return NULL;
    e->codec=avcodec_alloc_context3(codec);
    e->frame=av_frame_alloc(); e->packet=av_packet_alloc();
    if (!e->codec || !e->frame || !e->packet) goto fail;
    e->codec->sample_rate=48000;
    e->codec->sample_fmt=AV_SAMPLE_FMT_FLTP;
    e->codec->bit_rate=bitrate;
    e->codec->time_base=(AVRational){1,48000};
    e->codec->thread_count=1;
    const AVChannelLayout *layouts=NULL;
    int layout_count=0;
    if (avcodec_get_supported_config(e->codec,codec,AV_CODEC_CONFIG_CHANNEL_LAYOUT,0,(const void**)&layouts,&layout_count)==0 && layouts) {
        for (int i=0;i<layout_count;i++) if (layouts[i].nb_channels==6) {
            if (av_channel_layout_copy(&e->codec->ch_layout,&layouts[i])<0) goto fail;
            break;
        }
    }
    if (e->codec->ch_layout.nb_channels!=6) av_channel_layout_default(&e->codec->ch_layout,6);
    if (avcodec_open2(e->codec,codec,NULL)<0 || e->codec->frame_size<=0 || e->codec->frame_size>3072) goto fail;
    e->frame->format=AV_SAMPLE_FMT_FLTP; e->frame->sample_rate=48000;
    e->frame->nb_samples=e->codec->frame_size;
    if (av_channel_layout_copy(&e->frame->ch_layout,&e->codec->ch_layout)<0 || av_frame_get_buffer(e->frame,0)<0) goto fail;
    if (swr_alloc_set_opts2(&e->resampler,&e->codec->ch_layout,AV_SAMPLE_FMT_FLTP,48000,
                          &e->codec->ch_layout,AV_SAMPLE_FMT_S16,sample_rate,0,NULL)<0 || swr_init(e->resampler)<0) goto fail;
    e->fifo=av_audio_fifo_alloc(AV_SAMPLE_FMT_FLTP,6,e->codec->frame_size*16);
    e->converted_capacity=(int)(((int64_t)1280*48000+sample_rate-1)/sample_rate)+128;
    if (!e->fifo || av_samples_alloc_array_and_samples(&e->converted,NULL,6,e->converted_capacity,AV_SAMPLE_FMT_FLTP,0)<0) goto fail;
    return e;
fail:
    mybv_encoder_free(e); return NULL;
}

int mybv_encoder_delay(void *opaque) { MybvEncoder *e=opaque; return e ? e->codec->initial_padding : 0; }
int mybv_encoder_frame_size(void *opaque) { MybvEncoder *e=opaque; return e ? e->codec->frame_size : 0; }

int mybv_encoder_push(void *opaque,const uint8_t *pcm,int frames) {
    MybvEncoder *e=opaque;
    if (!e || !pcm || frames<=0 || frames>1024 || e->finishing) return AVERROR(EINVAL);
    if (av_audio_fifo_size(e->fifo)+e->converted_capacity>e->codec->frame_size*16) return 0;
    const uint8_t *input[1]={pcm};
    int n=swr_convert(e->resampler,e->converted,e->converted_capacity,input,frames);
    if (n<0) return n;
    if (n && av_audio_fifo_write(e->fifo,(void**)e->converted,n)!=n) return AVERROR(ENOMEM);
    return frames;
}

void mybv_encoder_finish(void *opaque) { MybvEncoder *e=opaque; if (e) e->finishing=1; }

static int pump_frame(MybvEncoder *e) {
    if (e->finishing && !e->resampler_flushed) {
        int n=swr_convert(e->resampler,e->converted,e->converted_capacity,NULL,0);
        if (n<0) return n;
        if (n && av_audio_fifo_write(e->fifo,(void**)e->converted,n)!=n) return AVERROR(ENOMEM);
        if (!n) e->resampler_flushed=1;
    }
    if (e->finishing && e->resampler_flushed && !e->tail_added) {
        int padding=e->codec->initial_padding;
        if (padding>e->converted_capacity) return AVERROR(EINVAL);
        if (padding>0) {
            av_samples_set_silence(e->converted,0,padding,6,AV_SAMPLE_FMT_FLTP);
            if (av_audio_fifo_write(e->fifo,(void**)e->converted,padding)!=padding) return AVERROR(ENOMEM);
        }
        e->tail_added=1;
    }
    int available=av_audio_fifo_size(e->fifo);
    if (!e->pending_frame && (available>=e->codec->frame_size || (e->finishing && e->resampler_flushed && available>0))) {
        int n=available<e->codec->frame_size ? available : e->codec->frame_size;
        if (av_frame_make_writable(e->frame)<0) return AVERROR(ENOMEM);
        av_samples_set_silence(e->frame->data,0,e->codec->frame_size,6,AV_SAMPLE_FMT_FLTP);
        if (av_audio_fifo_read(e->fifo,(void**)e->frame->data,n)!=n) return AVERROR(EINVAL);
        e->frame->pts=e->next_pts; e->pending_frame=1;
    }
    if (e->pending_frame) {
        int result=avcodec_send_frame(e->codec,e->frame);
        if (result==0) { e->next_pts+=e->codec->frame_size; e->pending_frame=0; return 1; }
        return result==AVERROR(EAGAIN) ? 0 : result;
    }
    if (e->finishing && e->resampler_flushed && !available && !e->eos_sent) {
        int result=avcodec_send_frame(e->codec,NULL);
        if (result==0 || result==AVERROR_EOF) { e->eos_sent=1; return 1; }
        return result==AVERROR(EAGAIN) ? 0 : result;
    }
    return 0;
}

/* Copies one complete access unit; 0=try later, -1=end, other negatives=error. */
int mybv_encoder_read(void *opaque,uint8_t *output,int capacity) {
    MybvEncoder *e=opaque;
    if (!e || !output || capacity<=0) return AVERROR(EINVAL);
    if (e->output_ended) return -1;
    for (int attempt=0;attempt<8;attempt++) {
        if (e->pending_packet) {
            if (capacity<e->packet->size) return AVERROR(ENOSPC);
            int size=e->packet->size;
            memcpy(output,e->packet->data,size);
            av_packet_unref(e->packet); e->pending_packet=0; return size;
        }
        int result=avcodec_receive_packet(e->codec,e->packet);
        if (result==0) { e->pending_packet=1; continue; }
        if (result==AVERROR_EOF) { e->output_ended=1; return -1; }
        if (result!=AVERROR(EAGAIN)) return result;
        int sent=pump_frame(e);
        if (sent<0) return sent;
        if (!sent) return 0;
    }
    return 0;
}
