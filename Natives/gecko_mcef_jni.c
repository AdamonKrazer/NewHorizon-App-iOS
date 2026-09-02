#include "jni.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>
#include <IOSurface/IOSurfaceRef.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define NH_MAX_BROWSERS 128
#define NH_MAX_EVENTS 256
#define NH_GPU_SLOTS 3
#define NH_GL_TEXTURE_2D 0x0DE1
#define NH_GL_UNSIGNED_BYTE 0x1401
#define NH_GL_RGBA 0x1908
#define NH_GL_BGRA_EXT 0x80E1

typedef void (*NHGLBindTexture)(uint32_t target, uint32_t texture);
typedef void (*NHReynardCommand)(
        const char *operation, const char *const *arguments, int32_t count);

typedef struct {
    IOSurfaceRef surface;
    EGLSurface egl_surface;
    EGLSyncKHR consumer_fence;
    uint32_t texture;
    uint64_t version;
    int width;
    int height;
    int awaiting_consumer_fence;
} NHGpuSlot;

typedef struct {
    pthread_mutex_t lock;
    IOSurfaceRef pending_surface;
    uint64_t pending_version;
    int pending_width;
    int pending_height;
    EGLDisplay display;
    EGLContext context;
    EGLConfig config;
    NHGpuSlot slots[NH_GPU_SLOTS];
    int current_slot;
    int registered;
    int release_requested;
} NHGpuBrowser;

typedef struct {
    int browser_id;
    char *type;
    char *payload;
} NHBrowserEvent;

static NHGpuBrowser g_browsers[NH_MAX_BROWSERS];
static pthread_once_t g_browsers_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t g_events_lock = PTHREAD_MUTEX_INITIALIZER;
static NHBrowserEvent g_events[NH_MAX_EVENTS];
static int g_event_head;
static int g_event_count;
static pthread_mutex_t g_work_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_work_condition = PTHREAD_COND_INITIALIZER;
static uint64_t g_work_generation;
static pthread_once_t g_gpu_api_once = PTHREAD_ONCE_INIT;
static PFNEGLCREATESYNCKHRPROC g_egl_create_sync;
static PFNEGLCLIENTWAITSYNCKHRPROC g_egl_client_wait_sync;
static PFNEGLDESTROYSYNCKHRPROC g_egl_destroy_sync;
static NHGLBindTexture g_gl_bind_texture;
static int g_gpu_api_available;

static void nh_initialize_browsers(void) {
    for (int index = 0; index < NH_MAX_BROWSERS; ++index) {
        pthread_mutex_init(&g_browsers[index].lock, NULL);
        g_browsers[index].display = EGL_NO_DISPLAY;
        g_browsers[index].context = EGL_NO_CONTEXT;
        g_browsers[index].current_slot = -1;
        for (int slot = 0; slot < NH_GPU_SLOTS; ++slot) {
            g_browsers[index].slots[slot].egl_surface = EGL_NO_SURFACE;
            g_browsers[index].slots[slot].consumer_fence = EGL_NO_SYNC_KHR;
        }
    }
}

static NHGpuBrowser *nh_browser_for(int browser_id) {
    pthread_once(&g_browsers_once, nh_initialize_browsers);
    if (browser_id < 0 || browser_id >= NH_MAX_BROWSERS) return NULL;
    return &g_browsers[browser_id];
}

static void nh_initialize_gpu_api(void) {
    g_egl_create_sync = (PFNEGLCREATESYNCKHRPROC)
            eglGetProcAddress("eglCreateSyncKHR");
    g_egl_client_wait_sync = (PFNEGLCLIENTWAITSYNCKHRPROC)
            eglGetProcAddress("eglClientWaitSyncKHR");
    g_egl_destroy_sync = (PFNEGLDESTROYSYNCKHRPROC)
            eglGetProcAddress("eglDestroySyncKHR");
    g_gl_bind_texture = (NHGLBindTexture) dlsym(RTLD_DEFAULT, "glBindTexture");
    g_gpu_api_available = g_egl_create_sync && g_egl_client_wait_sync
            && g_egl_destroy_sync && g_gl_bind_texture;
}

static void nh_signal_work(void) {
    pthread_mutex_lock(&g_work_lock);
    ++g_work_generation;
    pthread_cond_broadcast(&g_work_condition);
    pthread_mutex_unlock(&g_work_lock);
}

static void nh_release_surface(IOSurfaceRef surface) {
    if (surface == NULL) return;
    IOSurfaceDecrementUseCount(surface);
    CFRelease(surface);
}

static IOSurfaceRef nh_retain_surface(IOSurfaceRef surface) {
    if (surface == NULL) return NULL;
    CFRetain(surface);
    IOSurfaceIncrementUseCount(surface);
    return surface;
}

/* Gecko reports only a new IOSurface handle; no pixel buffer crosses CPUs. */
JNIEXPORT void nh_reynard_submit_gpu_surface(
        int32_t browser_id, uint32_t surface_id, int32_t width,
        int32_t height, uint64_t version) {
    NHGpuBrowser *browser = nh_browser_for(browser_id);
    if (browser == NULL || surface_id == 0 || width <= 0 || height <= 0
            || version == 0) {
        return;
    }
    IOSurfaceRef surface = IOSurfaceLookup(surface_id);
    if (surface == NULL) return;
    IOSurfaceIncrementUseCount(surface);

    pthread_mutex_lock(&browser->lock);
    if (version > browser->pending_version) {
        IOSurfaceRef previous = browser->pending_surface;
        browser->pending_surface = surface;
        browser->pending_version = version;
        browser->pending_width = width;
        browser->pending_height = height;
        surface = NULL;
        nh_release_surface(previous);
    }
    pthread_mutex_unlock(&browser->lock);
    nh_release_surface(surface);
    nh_signal_work();
}

JNIEXPORT void nh_reynard_submit_event(
        int32_t browser_id, const char *type, const char *payload) {
    pthread_mutex_lock(&g_events_lock);
    if (g_event_count == NH_MAX_EVENTS) {
        NHBrowserEvent *oldest = &g_events[g_event_head];
        free(oldest->type);
        free(oldest->payload);
        memset(oldest, 0, sizeof(*oldest));
        g_event_head = (g_event_head + 1) % NH_MAX_EVENTS;
        --g_event_count;
    }
    int tail = (g_event_head + g_event_count) % NH_MAX_EVENTS;
    g_events[tail].browser_id = browser_id;
    g_events[tail].type = strdup(type == NULL ? "" : type);
    g_events[tail].payload = strdup(payload == NULL ? "" : payload);
    ++g_event_count;
    pthread_mutex_unlock(&g_events_lock);
    nh_signal_work();
}

/* Destruction can arrive off the Minecraft GL thread. Actual EGL retirement
 * remains deferred until nativeGpuSharedTexture is called by that thread. */
JNIEXPORT void nh_reynard_release_browser(int32_t browser_id) {
    NHGpuBrowser *browser = nh_browser_for(browser_id);
    if (browser == NULL) return;
    pthread_mutex_lock(&browser->lock);
    browser->release_requested = 1;
    IOSurfaceRef pending = browser->pending_surface;
    browser->pending_surface = NULL;
    browser->pending_version = 0;
    browser->pending_width = 0;
    browser->pending_height = 0;
    pthread_mutex_unlock(&browser->lock);
    nh_release_surface(pending);
    nh_signal_work();
}

static int nh_current_context_matches(const NHGpuBrowser *browser) {
    return browser->registered
            && eglGetCurrentDisplay() == browser->display
            && eglGetCurrentContext() == browser->context;
}

static void nh_destroy_gpu_slot(NHGpuBrowser *browser, NHGpuSlot *slot) {
    if (slot->consumer_fence != EGL_NO_SYNC_KHR) {
        g_egl_destroy_sync(browser->display, slot->consumer_fence);
        slot->consumer_fence = EGL_NO_SYNC_KHR;
    }
    if (slot->egl_surface != EGL_NO_SURFACE) {
        g_gl_bind_texture(NH_GL_TEXTURE_2D, slot->texture);
        eglReleaseTexImage(browser->display, slot->egl_surface, EGL_BACK_BUFFER);
        eglDestroySurface(browser->display, slot->egl_surface);
        slot->egl_surface = EGL_NO_SURFACE;
    }
    nh_release_surface(slot->surface);
    slot->surface = NULL;
    slot->version = 0;
    slot->width = 0;
    slot->height = 0;
    slot->awaiting_consumer_fence = 0;
}

static void nh_abandon_gpu_slots(NHGpuBrowser *browser) {
    for (int index = 0; index < NH_GPU_SLOTS; ++index) {
        NHGpuSlot *slot = &browser->slots[index];
        if (slot->consumer_fence != EGL_NO_SYNC_KHR) {
            g_egl_destroy_sync(browser->display, slot->consumer_fence);
        }
        if (slot->egl_surface != EGL_NO_SURFACE) {
            eglDestroySurface(browser->display, slot->egl_surface);
        }
        nh_release_surface(slot->surface);
        uint32_t texture = slot->texture;
        memset(slot, 0, sizeof(*slot));
        slot->texture = texture;
        slot->egl_surface = EGL_NO_SURFACE;
        slot->consumer_fence = EGL_NO_SYNC_KHR;
    }
    browser->current_slot = -1;
}

static void nh_destroy_gpu_slots(NHGpuBrowser *browser) {
    if (browser->display != EGL_NO_DISPLAY) {
        for (int index = 0; index < NH_GPU_SLOTS; ++index) {
            nh_destroy_gpu_slot(browser, &browser->slots[index]);
        }
    }
    browser->current_slot = -1;
}

static int nh_choose_egl_config(
        EGLDisplay display, EGLContext context, EGLConfig *config_out) {
    EGLint config_id = 0;
    if (!eglQueryContext(display, context, EGL_CONFIG_ID, &config_id)) {
        return 0;
    }
    const EGLint attributes[] = {
        EGL_CONFIG_ID, config_id,
        EGL_NONE,
    };
    EGLint count = 0;
    return eglChooseConfig(display, attributes, config_out, 1, &count)
            && count == 1;
}

static int nh_has_iosurface_extension(EGLDisplay display) {
    const char *extensions = eglQueryString(display, EGL_EXTENSIONS);
    return extensions != NULL
            && strstr(extensions, "EGL_ANGLE_iosurface_client_buffer") != NULL;
}

static int nh_bind_surface_to_slot(
        NHGpuBrowser *browser, NHGpuSlot *slot, IOSurfaceRef surface,
        int width, int height, uint64_t version) {
    OSType pixel_format = IOSurfaceGetPixelFormat(surface);
    EGLint internal_format = pixel_format == 'BGRA'
            ? NH_GL_BGRA_EXT : NH_GL_RGBA;
    const EGLint attributes[] = {
        EGL_WIDTH, width,
        EGL_HEIGHT, height,
        EGL_IOSURFACE_PLANE_ANGLE, 0,
        EGL_TEXTURE_TARGET, EGL_TEXTURE_2D,
        EGL_TEXTURE_FORMAT, EGL_TEXTURE_RGBA,
        EGL_TEXTURE_TYPE_ANGLE, NH_GL_UNSIGNED_BYTE,
        EGL_TEXTURE_INTERNAL_FORMAT_ANGLE, internal_format,
        EGL_IOSURFACE_USAGE_HINT_ANGLE, EGL_IOSURFACE_READ_HINT_ANGLE,
        EGL_NONE,
    };

    EGLSurface egl_surface = eglCreatePbufferFromClientBuffer(
            browser->display, EGL_IOSURFACE_ANGLE,
            (EGLClientBuffer) surface, browser->config, attributes);
    if (egl_surface == EGL_NO_SURFACE) {
        fprintf(stderr,
                "[NewHorizon/Reynard] IOSurface pbuffer failed error=0x%x\n",
                eglGetError());
        return 0;
    }
    g_gl_bind_texture(NH_GL_TEXTURE_2D, slot->texture);
    if (!eglBindTexImage(browser->display, egl_surface, EGL_BACK_BUFFER)) {
        fprintf(stderr,
                "[NewHorizon/Reynard] IOSurface texture bind failed error=0x%x\n",
                eglGetError());
        eglDestroySurface(browser->display, egl_surface);
        return 0;
    }
    slot->surface = nh_retain_surface(surface);
    slot->egl_surface = egl_surface;
    slot->version = version;
    slot->width = width;
    slot->height = height;
    slot->awaiting_consumer_fence = 1;
    return 1;
}

static void nh_publish_consumer_fence(NHGpuBrowser *browser) {
    if (browser->current_slot < 0) return;
    NHGpuSlot *slot = &browser->slots[browser->current_slot];
    if (slot->consumer_fence != EGL_NO_SYNC_KHR || slot->version == 0) return;
    const EGLint attributes[] = {EGL_NONE};
    slot->consumer_fence = g_egl_create_sync(
            browser->display, EGL_SYNC_FENCE_KHR, attributes);
    if (slot->consumer_fence != EGL_NO_SYNC_KHR) {
        slot->awaiting_consumer_fence = 0;
    }
}

static int nh_slot_is_recyclable(NHGpuBrowser *browser, int index) {
    NHGpuSlot *slot = &browser->slots[index];
    if (index == browser->current_slot) return 0;
    if (slot->version == 0) return 1;
    if (slot->awaiting_consumer_fence) return 0;
    if (slot->consumer_fence == EGL_NO_SYNC_KHR) return 1;
    EGLint result = g_egl_client_wait_sync(
            browser->display, slot->consumer_fence, 0, 0);
    if (result != EGL_CONDITION_SATISFIED_KHR) return 0;
    g_egl_destroy_sync(browser->display, slot->consumer_fence);
    slot->consumer_fence = EGL_NO_SYNC_KHR;
    return 1;
}

static jstring nh_native_command(JNIEnv *env, jstring operation, jobjectArray args) {
    const char *operation_chars = operation == NULL
            ? "" : (*env)->GetStringUTFChars(env, operation, NULL);
    jsize count = args == NULL ? 0 : (*env)->GetArrayLength(env, args);
    const char **argument_chars = count == 0
            ? NULL : calloc((size_t) count, sizeof(*argument_chars));
    jstring *argument_strings = count == 0
            ? NULL : calloc((size_t) count, sizeof(*argument_strings));
    if (count > 0 && (argument_chars == NULL || argument_strings == NULL)) {
        free(argument_chars);
        free(argument_strings);
        if (operation != NULL) {
            (*env)->ReleaseStringUTFChars(env, operation, operation_chars);
        }
        return (*env)->NewStringUTF(env, "false");
    }
    for (jsize index = 0; index < count; ++index) {
        argument_strings[index] = (jstring) (*env)->GetObjectArrayElement(env, args, index);
        argument_chars[index] = argument_strings[index] == NULL ? ""
                : (*env)->GetStringUTFChars(env, argument_strings[index], NULL);
    }
    NHReynardCommand command = (NHReynardCommand) dlsym(
            RTLD_DEFAULT, "NHReynardHandleCommand");
    if (command != NULL) {
        command(operation_chars, argument_chars, (int32_t) count);
    }
    for (jsize index = 0; index < count; ++index) {
        if (argument_strings[index] != NULL) {
            (*env)->ReleaseStringUTFChars(
                    env, argument_strings[index], argument_chars[index]);
            (*env)->DeleteLocalRef(env, argument_strings[index]);
        }
    }
    free(argument_chars);
    free(argument_strings);
    if (operation != NULL) {
        (*env)->ReleaseStringUTFChars(env, operation, operation_chars);
    }
    return (*env)->NewStringUTF(env, command == NULL ? "false" : "true");
}

static jobjectArray nh_native_poll_event(JNIEnv *env) {
    pthread_mutex_lock(&g_events_lock);
    if (g_event_count == 0) {
        pthread_mutex_unlock(&g_events_lock);
        return NULL;
    }
    NHBrowserEvent event = g_events[g_event_head];
    memset(&g_events[g_event_head], 0, sizeof(event));
    g_event_head = (g_event_head + 1) % NH_MAX_EVENTS;
    --g_event_count;
    pthread_mutex_unlock(&g_events_lock);

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, 3, string_class, NULL);
    char browser_id[24];
    snprintf(browser_id, sizeof(browser_id), "%d", event.browser_id);
    jstring values[3] = {
        (*env)->NewStringUTF(env, browser_id),
        (*env)->NewStringUTF(env, event.type == NULL ? "" : event.type),
        (*env)->NewStringUTF(env, event.payload == NULL ? "" : event.payload),
    };
    for (int index = 0; index < 3; ++index) {
        (*env)->SetObjectArrayElement(env, result, index, values[index]);
        (*env)->DeleteLocalRef(env, values[index]);
    }
    free(event.type);
    free(event.payload);
    return result;
}

static jlong nh_native_wait_for_work(jlong observed_generation, jint timeout_ms) {
    pthread_mutex_lock(&g_work_lock);
    if (g_work_generation == (uint64_t) observed_generation && timeout_ms > 0) {
        struct timespec deadline;
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += timeout_ms / 1000;
        deadline.tv_nsec += (long) (timeout_ms % 1000) * 1000000L;
        if (deadline.tv_nsec >= 1000000000L) {
            ++deadline.tv_sec;
            deadline.tv_nsec -= 1000000000L;
        }
        pthread_cond_timedwait(&g_work_condition, &g_work_lock, &deadline);
    }
    jlong generation = (jlong) g_work_generation;
    pthread_mutex_unlock(&g_work_lock);
    return generation;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    pthread_once(&g_gpu_api_once, nh_initialize_gpu_api);
    return JNI_VERSION_1_6;
}

#define NH_BOOTSTRAP(name) \
    Java_com_newhorizon_cefubuntu_CefUbuntuBootstrapMain_##name

JNIEXPORT jstring JNICALL NH_BOOTSTRAP(nativeCommand)(
        JNIEnv *env, jclass clazz, jstring operation, jobjectArray args) {
    (void) clazz;
    return nh_native_command(env, operation, args);
}

/* CPU frame transport is intentionally absent on iOS. */
JNIEXPORT jlong JNICALL NH_BOOTSTRAP(nativeFrameInfo)(
        JNIEnv *env, jclass clazz, jint browser_id, jintArray metadata) {
    (void) env; (void) clazz; (void) browser_id; (void) metadata;
    return 0;
}

JNIEXPORT jboolean JNICALL NH_BOOTSTRAP(nativeCopyFrame)(
        JNIEnv *env, jclass clazz, jint browser_id, jobject target,
        jlong expected_version) {
    (void) env; (void) clazz; (void) browser_id; (void) target;
    (void) expected_version;
    return JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL NH_BOOTSTRAP(nativePollEvent)(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    return nh_native_poll_event(env);
}

JNIEXPORT jlong JNICALL NH_BOOTSTRAP(nativeGpuFrameInfo)(
        JNIEnv *env, jclass clazz, jint browser_id, jintArray metadata) {
    (void) clazz;
    NHGpuBrowser *browser = nh_browser_for(browser_id);
    if (browser == NULL) return 0;
    pthread_mutex_lock(&browser->lock);
    jint values[] = {
        browser->pending_width,
        browser->pending_height,
        g_gpu_api_available ? 0 : 1,
        browser->current_slot >= 0
                ? (jint) browser->slots[browser->current_slot].version : 0,
    };
    if (metadata != NULL && (*env)->GetArrayLength(env, metadata) >= 4) {
        (*env)->SetIntArrayRegion(env, metadata, 0, 4, values);
    }
    jlong version = (jlong) browser->pending_version;
    pthread_mutex_unlock(&browser->lock);
    return version;
}

JNIEXPORT jboolean JNICALL NH_BOOTSTRAP(nativeGpuRegisterConsumerTextures)(
        JNIEnv *env, jclass clazz, jint browser_id, jint width, jint height,
        jintArray texture_ids) {
    (void) clazz;
    pthread_once(&g_gpu_api_once, nh_initialize_gpu_api);
    NHGpuBrowser *browser = nh_browser_for(browser_id);
    if (!g_gpu_api_available || browser == NULL || texture_ids == NULL
            || width <= 0 || height <= 0
            || (*env)->GetArrayLength(env, texture_ids) < NH_GPU_SLOTS) {
        return JNI_FALSE;
    }
    EGLDisplay display = eglGetCurrentDisplay();
    EGLContext context = eglGetCurrentContext();
    EGLConfig config = NULL;
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT
            || !nh_has_iosurface_extension(display)
            || !nh_choose_egl_config(display, context, &config)) {
        return JNI_FALSE;
    }
    jint ids[NH_GPU_SLOTS] = {0};
    (*env)->GetIntArrayRegion(env, texture_ids, 0, NH_GPU_SLOTS, ids);
    for (int index = 0; index < NH_GPU_SLOTS; ++index) {
        if (ids[index] <= 0) return JNI_FALSE;
    }

    pthread_mutex_lock(&browser->lock);
    if (browser->registered && (browser->display != display
            || browser->context != context)) {
        nh_abandon_gpu_slots(browser);
        browser->registered = 0;
    }
    if (browser->registered) {
        int storage_changed = 0;
        for (int index = 0; index < NH_GPU_SLOTS; ++index) {
            NHGpuSlot *slot = &browser->slots[index];
            storage_changed |= slot->texture != (uint32_t) ids[index];
            storage_changed |= slot->version != 0
                    && (slot->width != width || slot->height != height);
        }
        if (storage_changed) {
            nh_destroy_gpu_slots(browser);
        }
    }
    browser->display = display;
    browser->context = context;
    browser->config = config;
    for (int index = 0; index < NH_GPU_SLOTS; ++index) {
        browser->slots[index].texture = (uint32_t) ids[index];
    }
    browser->registered = 1;
    browser->release_requested = 0;
    pthread_mutex_unlock(&browser->lock);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL NH_BOOTSTRAP(nativeGpuSharedTexture)(
        JNIEnv *env, jclass clazz, jint browser_id, jint width, jint height) {
    (void) env; (void) clazz;
    NHGpuBrowser *browser = nh_browser_for(browser_id);
    if (browser == NULL) return 0;
    pthread_mutex_lock(&browser->lock);
    if (!nh_current_context_matches(browser)) {
        pthread_mutex_unlock(&browser->lock);
        return -2;
    }
    if (width <= 0 || height <= 0 || browser->release_requested) {
        nh_destroy_gpu_slots(browser);
        browser->registered = 0;
        browser->release_requested = 0;
        pthread_mutex_unlock(&browser->lock);
        return -1;
    }

    NHGpuSlot *current = browser->current_slot >= 0
            ? &browser->slots[browser->current_slot] : NULL;
    if (current != NULL && current->version == browser->pending_version) {
        jint texture = (jint) current->texture;
        pthread_mutex_unlock(&browser->lock);
        return texture;
    }

    nh_publish_consumer_fence(browser);
    int selected = -1;
    for (int offset = 1; offset <= NH_GPU_SLOTS; ++offset) {
        int candidate = (browser->current_slot + offset + NH_GPU_SLOTS)
                % NH_GPU_SLOTS;
        if (nh_slot_is_recyclable(browser, candidate)) {
            selected = candidate;
            break;
        }
    }
    if (selected < 0 || browser->pending_surface == NULL) {
        jint texture = current == NULL ? 0 : (jint) current->texture;
        pthread_mutex_unlock(&browser->lock);
        return texture;
    }

    NHGpuSlot *slot = &browser->slots[selected];
    nh_destroy_gpu_slot(browser, slot);
    if (!nh_bind_surface_to_slot(
            browser, slot, browser->pending_surface,
            browser->pending_width, browser->pending_height,
            browser->pending_version)) {
        jint texture = current == NULL ? 0 : (jint) current->texture;
        pthread_mutex_unlock(&browser->lock);
        return texture;
    }
    browser->current_slot = selected;
    jint texture = (jint) slot->texture;
    pthread_mutex_unlock(&browser->lock);
    return texture;
}

JNIEXPORT jboolean JNICALL NH_BOOTSTRAP(nativeGpuBlit)(
        JNIEnv *env, jclass clazz, jint browser_id, jint texture_id,
        jint width, jint height) {
    (void) env; (void) clazz; (void) browser_id; (void) texture_id;
    (void) width; (void) height;
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL NH_BOOTSTRAP(nativeGpuDraw)(
        JNIEnv *env, jclass clazz, jint browser_id, jfloatArray vertices) {
    (void) env; (void) clazz; (void) browser_id; (void) vertices;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL NH_BOOTSTRAP(nativeGpuReset)(
        JNIEnv *env, jclass clazz, jint browser_id) {
    (void) env; (void) clazz;
    NHGpuBrowser *browser = nh_browser_for(browser_id);
    if (browser == NULL) return;
    pthread_mutex_lock(&browser->lock);
    if (nh_current_context_matches(browser)) {
        nh_destroy_gpu_slots(browser);
    }
    pthread_mutex_unlock(&browser->lock);
}

JNIEXPORT jlong JNICALL NH_BOOTSTRAP(nativeWaitForWork)(
        JNIEnv *env, jclass clazz, jlong observed_generation, jint timeout_ms) {
    (void) env; (void) clazz;
    return nh_native_wait_for_work(observed_generation, timeout_ms);
}
