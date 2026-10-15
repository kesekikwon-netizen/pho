// 사진측량 — AprilTag 3 (BSD-2) JNI 래퍼. 결과: 표적당 12개 double [id, hamming, cx, cy, x0,y0..x3,y3]
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "apriltag.h"
#include "tag36h11.h"
#include "tag25h9.h"
#include "tag16h5.h"
#include "tagStandard41h12.h"

static apriltag_family_t *make_family(int f) {
    switch (f) {
        case 1: return tag25h9_create();
        case 2: return tag16h5_create();
        case 3: return tagStandard41h12_create();
        default: return tag36h11_create();
    }
}
static void free_family(int f, apriltag_family_t *tf) {
    switch (f) {
        case 1: tag25h9_destroy(tf); break;
        case 2: tag16h5_destroy(tf); break;
        case 3: tagStandard41h12_destroy(tf); break;
        default: tag36h11_destroy(tf);
    }
}

JNIEXPORT jdoubleArray JNICALL Java_kr_archsurvey_pg_tag_AprilTagNative_detect(
        JNIEnv *env, jclass cls, jbyteArray gray, jint w, jint h, jint family, jfloat decimate, jint threads, jint maxHamming) {
    jsize n = (*env)->GetArrayLength(env, gray);
    if (w <= 0 || h <= 0 || n < w * h) return (*env)->NewDoubleArray(env, 0);
    jbyte *px = (*env)->GetByteArrayElements(env, gray, NULL);
    image_u8_t im = { .width = w, .height = h, .stride = w, .buf = (uint8_t *) px };
    apriltag_family_t *tf = make_family(family);
    apriltag_detector_t *td = apriltag_detector_create();
    apriltag_detector_add_family_bits(td, tf, maxHamming < 0 ? 0 : (maxHamming > 2 ? 2 : maxHamming));
    td->quad_decimate = decimate < 1.f ? 1.f : decimate;
    td->nthreads = threads < 1 ? 1 : threads;
    td->refine_edges = 1;
    zarray_t *dets = apriltag_detector_detect(td, &im);
    int cnt = zarray_size(dets);
    jdoubleArray out = (*env)->NewDoubleArray(env, cnt * 12);
    double *buf = (double *) malloc(sizeof(double) * (cnt * 12 + 1));
    for (int i = 0; i < cnt; i++) {
        apriltag_detection_t *d; zarray_get(dets, i, &d);
        double *o = buf + i * 12;
        o[0] = d->id; o[1] = d->hamming; o[2] = d->c[0]; o[3] = d->c[1];
        for (int k = 0; k < 4; k++) { o[4 + 2 * k] = d->p[k][0]; o[5 + 2 * k] = d->p[k][1]; }
    }
    if (cnt > 0) (*env)->SetDoubleArrayRegion(env, out, 0, cnt * 12, buf);
    free(buf);
    apriltag_detections_destroy(dets);
    apriltag_detector_destroy(td);
    free_family(family, tf);
    (*env)->ReleaseByteArrayElements(env, gray, px, JNI_ABORT);
    return out;
}
