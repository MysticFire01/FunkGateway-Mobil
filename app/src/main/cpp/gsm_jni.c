#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "gsm.h"

#define PACKET_BYTES 325
#define PCM_SAMPLES 1600
#define PCM_BYTES 3200
#define GSM_OPT_WAV49_MODE 4

typedef struct {
    gsm rx;
    gsm tx;
} FgwGsmCodec;

static void destroy_codec(FgwGsmCodec *codec) {
    if (!codec) return;
    if (codec->rx) gsm_destroy(codec->rx);
    if (codec->tx) gsm_destroy(codec->tx);
    free(codec);
}

JNIEXPORT jlong JNICALL
Java_org_funkgateway_mobile_audio_GsmWav49Codec_nativeCreate(
    JNIEnv *env, jobject self
) {
    (void)env;
    (void)self;

    FgwGsmCodec *codec = calloc(1, sizeof(*codec));
    if (!codec) return 0;

    codec->rx = gsm_create();
    codec->tx = gsm_create();

    if (!codec->rx || !codec->tx) {
        destroy_codec(codec);
        return 0;
    }

    int wav49 = 1;
    if (gsm_option(codec->rx, GSM_OPT_WAV49_MODE, &wav49) == -1) {
        destroy_codec(codec);
        return 0;
    }

    wav49 = 1;
    if (gsm_option(codec->tx, GSM_OPT_WAV49_MODE, &wav49) == -1) {
        destroy_codec(codec);
        return 0;
    }

    return (jlong)(intptr_t)codec;
}

JNIEXPORT void JNICALL
Java_org_funkgateway_mobile_audio_GsmWav49Codec_nativeDestroy(
    JNIEnv *env, jobject self, jlong handle
) {
    (void)env;
    (void)self;
    destroy_codec((FgwGsmCodec *)(intptr_t)handle);
}

JNIEXPORT jbyteArray JNICALL
Java_org_funkgateway_mobile_audio_GsmWav49Codec_nativeDecode(
    JNIEnv *env, jobject self, jlong handle, jbyteArray input
) {
    (void)self;
    FgwGsmCodec *codec = (FgwGsmCodec *)(intptr_t)handle;

    if (!codec || !codec->rx || !input ||
        (*env)->GetArrayLength(env, input) != PACKET_BYTES) {
        return NULL;
    }

    unsigned char packet[PACKET_BYTES + 1] = {0};
    gsm_signal samples[PCM_SAMPLES];

    (*env)->GetByteArrayRegion(
        env, input, 0, PACKET_BYTES, (jbyte *)packet
    );
    if ((*env)->ExceptionCheck(env)) return NULL;

    for (int block = 0; block < 5; block++) {
        unsigned char *src = packet + block * 65;
        gsm_signal *dst = samples + block * 320;

        if (gsm_decode(codec->rx, src, dst) == -1 ||
            gsm_decode(codec->rx, src + 33, dst + 160) == -1) {
            return NULL;
        }
    }

    jbyteArray result = (*env)->NewByteArray(env, PCM_BYTES);
    if (!result) return NULL;

    (*env)->SetByteArrayRegion(
        env, result, 0, PCM_BYTES, (const jbyte *)samples
    );
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_org_funkgateway_mobile_audio_GsmWav49Codec_nativeEncode(
    JNIEnv *env, jobject self, jlong handle, jbyteArray input
) {
    (void)self;
    FgwGsmCodec *codec = (FgwGsmCodec *)(intptr_t)handle;

    if (!codec || !codec->tx || !input ||
        (*env)->GetArrayLength(env, input) != PCM_BYTES) {
        return NULL;
    }

    gsm_signal samples[PCM_SAMPLES];
    unsigned char packet[PACKET_BYTES + 1] = {0};

    (*env)->GetByteArrayRegion(
        env, input, 0, PCM_BYTES, (jbyte *)samples
    );
    if ((*env)->ExceptionCheck(env)) return NULL;

    for (int block = 0; block < 5; block++) {
        gsm_signal *src = samples + block * 320;
        unsigned char *dst = packet + block * 65;

        gsm_encode(codec->tx, src, dst);
        gsm_encode(codec->tx, src + 160, dst + 32);
    }

    jbyteArray result = (*env)->NewByteArray(env, PACKET_BYTES);
    if (!result) return NULL;

    (*env)->SetByteArrayRegion(
        env, result, 0, PACKET_BYTES, (const jbyte *)packet
    );
    return result;
}
