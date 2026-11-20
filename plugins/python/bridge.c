#define PY_SSIZE_T_CLEAN
#include <Python.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static PyObject *dispatch;

static void fail(JNIEnv *env, const char *message) {
    if (!(*env)->ExceptionCheck(env))
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), message);
}

JNIEXPORT void JNICALL Java_xiao_bu_python_RuntimePlugin_start(JNIEnv *env, jclass type, jstring directory) {
    if (dispatch) return;
    if (Py_IsInitialized()) {
        fail(env, "Python bridge initialization failed; restart nTv");
        return;
    }
    const char *path = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!path) return;
    PyStatus status = PyStatus_Ok();
#if defined(__ANDROID_API__) && __ANDROID_API__ < 21
    PyPreConfig preconfig;
    PyPreConfig_InitIsolatedConfig(&preconfig);
    preconfig.utf8_mode = 1;
    status = Py_PreInitialize(&preconfig);
#endif
    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);
    config.write_bytecode = 0;
    config.install_signal_handlers = 0;
    config.site_import = 0;
    if (!PyStatus_Exception(status)) status = PyConfig_SetBytesString(&config, &config.home, path);
    if (!PyStatus_Exception(status)) status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    if (PyStatus_Exception(status)) {
        fail(env, status.err_msg ? status.err_msg : "Unable to start Python");
    } else {
        PyObject *root = PyUnicode_DecodeFSDefault(path);
        if (root && PyList_Insert(PySys_GetObject("path"), 0, root) == 0) {
            PyObject *module = PyImport_ImportModule("ntv_spiders");
            if (module) dispatch = PyObject_GetAttrString(module, "dispatch");
            Py_XDECREF(module);
        }
        Py_XDECREF(root);
        if (!dispatch) {
            PyErr_Clear();
            fail(env, "Unable to load Python spider bridge");
        }
        PyEval_SaveThread();
    }
    (*env)->ReleaseStringUTFChars(env, directory, path);
}

JNIEXPORT jbyteArray JNICALL Java_xiao_bu_python_RuntimePlugin_run(JNIEnv *env, jclass type, jbyteArray input) {
    if (!dispatch) {
        fail(env, "Python bridge is unavailable");
        return NULL;
    }
    jsize size = (*env)->GetArrayLength(env, input);
    jbyte *data = (*env)->GetByteArrayElements(env, input, NULL);
    if (!data) return NULL;
    PyGILState_STATE gil = PyGILState_Ensure();
    PyObject *arg = PyBytes_FromStringAndSize((const char *)data, size);
    (*env)->ReleaseByteArrayElements(env, input, data, JNI_ABORT);
    PyObject *result = arg ? PyObject_CallOneArg(dispatch, arg) : NULL;
    Py_XDECREF(arg);
    jbyteArray output = NULL;
    char *bytes;
    Py_ssize_t length;
    if (result && PyBytes_AsStringAndSize(result, &bytes, &length) == 0 && length <= 8 * 1024 * 1024) {
        output = (*env)->NewByteArray(env, (jsize)length);
        if (output) (*env)->SetByteArrayRegion(env, output, 0, (jsize)length, (const jbyte *)bytes);
    } else {
        PyErr_Clear();
        fail(env, "Invalid Python result");
    }
    Py_XDECREF(result);
    PyGILState_Release(gil);
    return output;
}
