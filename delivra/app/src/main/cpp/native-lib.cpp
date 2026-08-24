#include <jni.h>
#include <string>
#include <cstdlib>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>
#include "node.h"

#define LOG_TAG "NodeJS"

static int pfd[2];
static pthread_t thread_id;

static void* logger_thread(void*) {
    ssize_t rdsz;
    char buf[512];
    while ((rdsz = read(pfd[0], buf, sizeof(buf) - 1)) > 0) {
        buf[rdsz] = 0;
        __android_log_write(ANDROID_LOG_INFO, LOG_TAG, buf);
    }
    return 0;
}

static int start_logger() {
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    if (pipe(pfd) < 0) return -1;
    dup2(pfd[1], STDOUT_FILENO);
    dup2(pfd[1], STDERR_FILENO);

    if (pthread_create(&thread_id, NULL, logger_thread, NULL) != 0)
        return -1;
    pthread_detach(thread_id);
    return 0;
}

extern "C" jint JNICALL
Java_com_kabshah_delivra_service_SchedulerService_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments,
        jstring tmpDir) {

    start_logger();

    // Android has no writable /tmp; Node falls back to os.tmpdir() == '/tmp'
    // when TMPDIR is unset, which breaks Baileys media encryption/upload
    // (ENOENT on /tmp/document<id>-enc). Stock nodejs-mobile sets TMPDIR to the
    // app cache dir before node::Start(); replicate that here.
    if (tmpDir != nullptr) {
        const char* tmp = env->GetStringUTFChars(tmpDir, nullptr);
        if (tmp != nullptr) {
            setenv("TMPDIR", tmp, 1);
            env->ReleaseStringUTFChars(tmpDir, tmp);
        }
    }

    // argc
    jsize argument_count = env->GetArrayLength(arguments);

    // Compute byte size need for all arguments in contiguous memory
    int c_arguments_size = 0;
    for (int i = 0; i < argument_count ; i++) {
        c_arguments_size += strlen(env->GetStringUTFChars((jstring)env->GetObjectArrayElement(arguments, i), 0));
        c_arguments_size++; // for '\0'
    }

    // Stores arguments in contiguous memory
    char* args_buffer = (char*)calloc(c_arguments_size, sizeof(char));

    // argv to pass into node
    char* argv[argument_count];
    char* current_args_position = args_buffer;

    // Populate the args_buffer and argv
    for (int i = 0; i < argument_count ; i++) {
        const char* current_argument = env->GetStringUTFChars((jstring)env->GetObjectArrayElement(arguments, i), 0);
        strncpy(current_args_position, current_argument, strlen(current_argument));
        argv[i] = current_args_position;
        current_args_position += strlen(current_args_position) + 1;
    }

    // Start node with argc and argv
    return jint(node::Start(argument_count, argv));
}
