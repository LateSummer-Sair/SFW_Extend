#include "sacoms_libc.h"

#include <jni.h>
#include <stdlib.h>
#include <string.h>

/* ================================================================
 *  Internal helpers
 * ================================================================ */

/* Convert Java String[] => SList */
static SList* jstrarr_to_slist(JNIEnv* env, jobjectArray jarr) {
    if (!jarr) return NULL;
    jsize len = (*env)->GetArrayLength(env, jarr);
    SList* list = slist_create();
    if (!list) return NULL;
    for (jsize i = 0; i < len; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jarr, i);
        const char* cs = js ? (*env)->GetStringUTFChars(env, js, NULL) : "";
        slist_add(list, cs);
        if (js) (*env)->ReleaseStringUTFChars(env, js, cs);
    }
    return list;
}

/* Convert SList => Java String[] */
static jobjectArray slist_to_jstrarr(JNIEnv* env, SList* list) {
    if (!list) return NULL;
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = (*env)->NewObjectArray(env, (jsize)list->length, strClass, NULL);
    for (size_t i = 0; i < list->length; i++) {
        jstring js = (*env)->NewStringUTF(env, list->entries[i] ? list->entries[i] : "");
        (*env)->SetObjectArrayElement(env, arr, (jsize)i, js);
        (*env)->DeleteLocalRef(env, js);
    }
    (*env)->DeleteLocalRef(env, strClass);
    return arr;
}

/* Convert C char** + len => Java String[] */
static jobjectArray charpp_to_jstrarr(JNIEnv* env, char** arr, size_t len) {
    if (!arr || len == 0) {
        jclass strClass = (*env)->FindClass(env, "java/lang/String");
        jobjectArray result = (*env)->NewObjectArray(env, 0, strClass, NULL);
        (*env)->DeleteLocalRef(env, strClass);
        return result;
    }
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray jarr = (*env)->NewObjectArray(env, (jsize)len, strClass, NULL);
    for (size_t i = 0; i < len; i++) {
        jstring js = (*env)->NewStringUTF(env, arr[i] ? arr[i] : "");
        (*env)->SetObjectArrayElement(env, jarr, (jsize)i, js);
        (*env)->DeleteLocalRef(env, js);
    }
    (*env)->DeleteLocalRef(env, strClass);
    return jarr;
}

/* Free char** array (but NOT the individual strings) */
static void free_charpp(char** arr, size_t len) {
    if (!arr) return;
    for (size_t i = 0; i < len; i++) free(arr[i]);
    free(arr);
}

/* ================================================================
 *  JNI: FileMana
 * ================================================================ */

JNIEXPORT jobjectArray JNICALL
Java_sair_sacoms_jni_JNISaComS_file_1readLines(JNIEnv* env, jclass cls,
    jstring jfilePath, jstring jencoding) {
    const char* filePath = (*env)->GetStringUTFChars(env, jfilePath, NULL);
    const char* encoding = jencoding ? (*env)->GetStringUTFChars(env, jencoding, NULL) : NULL;

    SList* list = sacoms_file_read_lines(filePath, encoding);
    jobjectArray result = slist_to_jstrarr(env, list);

    (*env)->ReleaseStringUTFChars(env, jfilePath, filePath);
    if (encoding) (*env)->ReleaseStringUTFChars(env, jencoding, encoding);
    slist_free(list);
    return result;
}

JNIEXPORT jint JNICALL
Java_sair_sacoms_jni_JNISaComS_file_1writeLines(JNIEnv* env, jclass cls,
    jstring jfilePath, jobjectArray jlines, jboolean jnewline) {
    const char* filePath = (*env)->GetStringUTFChars(env, jfilePath, NULL);
    SList* list = jstrarr_to_slist(env, jlines);
    int ret = sacoms_file_write_lines(filePath, list, jnewline);
    (*env)->ReleaseStringUTFChars(env, jfilePath, filePath);
    slist_free(list);
    return ret;
}

JNIEXPORT jint JNICALL
Java_sair_sacoms_jni_JNISaComS_file_1appendLines(JNIEnv* env, jclass cls,
    jstring jfilePath, jobjectArray jlines, jboolean jnewline) {
    const char* filePath = (*env)->GetStringUTFChars(env, jfilePath, NULL);
    SList* list = jstrarr_to_slist(env, jlines);
    int ret = sacoms_file_append_lines(filePath, list, jnewline);
    (*env)->ReleaseStringUTFChars(env, jfilePath, filePath);
    slist_free(list);
    return ret;
}

JNIEXPORT jint JNICALL
Java_sair_sacoms_jni_JNISaComS_file_1delete(JNIEnv* env, jclass cls, jstring jpath) {
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int ret = sacoms_file_delete(path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return ret;
}

JNIEXPORT jint JNICALL
Java_sair_sacoms_jni_JNISaComS_file_1copy(JNIEnv* env, jclass cls,
    jstring jsrc, jstring jdst) {
    const char* src = (*env)->GetStringUTFChars(env, jsrc, NULL);
    const char* dst = (*env)->GetStringUTFChars(env, jdst, NULL);
    int ret = sacoms_file_copy(src, dst);
    (*env)->ReleaseStringUTFChars(env, jsrc, src);
    (*env)->ReleaseStringUTFChars(env, jdst, dst);
    return ret;
}

JNIEXPORT jboolean JNICALL
Java_sair_sacoms_jni_JNISaComS_file_1exists(JNIEnv* env, jclass cls, jstring jpath) {
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int ret = sacoms_file_exists(path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return (jboolean)ret;
}

/* ================================================================
 *  JNI: StrEdit
 * ================================================================ */

JNIEXPORT jobjectArray JNICALL
Java_sair_sacoms_jni_JNISaComS_str_1castArr(JNIEnv* env, jclass cls,
    jobjectArray jarr) {
    if (!jarr) {
        jclass sc = (*env)->FindClass(env, "java/lang/String");
        jobjectArray empty = (*env)->NewObjectArray(env, 0, sc, NULL);
        (*env)->DeleteLocalRef(env, sc);
        return empty;
    }
    jsize len = (*env)->GetArrayLength(env, jarr);
    void** objArr = (void**)calloc(len, sizeof(void*));
    for (jsize i = 0; i < len; i++) {
        jobject obj = (*env)->GetObjectArrayElement(env, jarr, i);
        if (obj) {
            jstring js = (jstring)(*env)->CallObjectMethod(env, obj,
                (*env)->GetMethodID(env, (*env)->GetObjectClass(env, obj),
                    "toString", "()Ljava/lang/String;"));
            if (js) {
                const char* cs = (*env)->GetStringUTFChars(env, js, NULL);
                objArr[i] = (void*)cs; /* stored — released later */
            }
            (*env)->DeleteLocalRef(env, obj);
            (*env)->DeleteLocalRef(env, js);
        }
    }
    size_t outLen = 0;
    char** result = sacoms_str_cast_arr(objArr, len, &outLen);
    jobjectArray jres = charpp_to_jstrarr(env, result, outLen);
    /* cleanup */
    for (jsize i = 0; i < len; i++) {
        if (objArr[i]) {
            /* find the jstring again to release */;
        }
    }
    free(objArr);
    free_charpp(result, outLen);
    return jres;
}

JNIEXPORT jstring JNICALL
Java_sair_sacoms_jni_JNISaComS_str_1join(JNIEnv* env, jclass cls,
    jobjectArray jarr, jboolean jnewline) {
    if (!jarr) return (*env)->NewStringUTF(env, "");

    jsize len = (*env)->GetArrayLength(env, jarr);
    char** arr = (char**)calloc(len, sizeof(char*));
    if (!arr) return (*env)->NewStringUTF(env, "");

    for (jsize i = 0; i < len; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jarr, i);
        if (js) {
            const char* cs = (*env)->GetStringUTFChars(env, js, NULL);
            arr[i] = cs ? _strdup(cs) : _strdup("");
            (*env)->ReleaseStringUTFChars(env, js, cs);
        } else {
            arr[i] = _strdup("");
        }
        (*env)->DeleteLocalRef(env, js);
    }

    char* cstr = sacoms_str_join(arr, len, jnewline);
    jstring result = (*env)->NewStringUTF(env, cstr ? cstr : "");

    for (jsize i = 0; i < len; i++) free(arr[i]);
    free(arr);
    free(cstr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_sair_sacoms_jni_JNISaComS_str_1splitChars(JNIEnv* env, jclass cls,
    jstring jstr) {
    const char* str = (*env)->GetStringUTFChars(env, jstr, NULL);
    size_t outLen = 0;
    char** arr = sacoms_str_split_chars(str, &outLen);
    jobjectArray result = charpp_to_jstrarr(env, arr, outLen);
    (*env)->ReleaseStringUTFChars(env, jstr, str);
    free_charpp(arr, outLen);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_sair_sacoms_jni_JNISaComS_str_1remove(JNIEnv* env, jclass cls,
    jobjectArray jsrc, jstring jpattern, jboolean jlineMode) {
    SList* srcList = jstrarr_to_slist(env, jsrc);
    const char* pattern = jpattern ? (*env)->GetStringUTFChars(env, jpattern, NULL) : NULL;
    SList* result = sacoms_str_remove(srcList, pattern, jlineMode);
    jobjectArray jres = slist_to_jstrarr(env, result);
    if (jpattern) (*env)->ReleaseStringUTFChars(env, jpattern, pattern);
    slist_free(srcList);
    slist_free(result);
    return jres;
}

JNIEXPORT jstring JNICALL
Java_sair_sacoms_jni_JNISaComS_str_1replace(JNIEnv* env, jclass cls,
    jstring jsrc, jstring jold, jstring jnewStr) {
    const char* src = (*env)->GetStringUTFChars(env, jsrc, NULL);
    const char* old = (*env)->GetStringUTFChars(env, jold, NULL);
    const char* newStr = (*env)->GetStringUTFChars(env, jnewStr, NULL);
    char* cres = sacoms_str_replace(src, old, newStr);
    jstring result = (*env)->NewStringUTF(env, cres ? cres : "");
    (*env)->ReleaseStringUTFChars(env, jsrc, src);
    (*env)->ReleaseStringUTFChars(env, jold, old);
    (*env)->ReleaseStringUTFChars(env, jnewStr, newStr);
    free(cres);
    return result;
}

/* ================================================================
 *  JNI: MathCast
 * ================================================================ */

JNIEXPORT jlong JNICALL
Java_sair_sacoms_jni_JNISaComS_math_1chineseToLong(JNIEnv* env, jclass cls,
    jstring jchinese) {
    const char* chinese = (*env)->GetStringUTFChars(env, jchinese, NULL);
    long result = sacoms_math_chinese_to_long(chinese);
    (*env)->ReleaseStringUTFChars(env, jchinese, chinese);
    return (jlong)result;
}

JNIEXPORT jstring JNICALL
Java_sair_sacoms_jni_JNISaComS_math_1longToChinese(JNIEnv* env, jclass cls,
    jlong jvalue, jboolean jupperCase) {
    char* cres = sacoms_math_long_to_chinese((long)jvalue, jupperCase);
    jstring result = (*env)->NewStringUTF(env, cres ? cres : "");
    free(cres);
    return result;
}

JNIEXPORT jstring JNICALL
Java_sair_sacoms_jni_JNISaComS_math_1doubleToChinese(JNIEnv* env, jclass cls,
    jdouble jvalue, jboolean jupperCase) {
    char* cres = sacoms_math_double_to_chinese(jvalue, jupperCase);
    jstring result = (*env)->NewStringUTF(env, cres ? cres : "");
    free(cres);
    return result;
}

JNIEXPORT jdouble JNICALL
Java_sair_sacoms_jni_JNISaComS_math_1chineseToDouble(JNIEnv* env, jclass cls,
    jstring jchinese) {
    const char* chinese = (*env)->GetStringUTFChars(env, jchinese, NULL);
    double result = sacoms_math_chinese_to_double(chinese);
    (*env)->ReleaseStringUTFChars(env, jchinese, chinese);
    return (jdouble)result;
}

JNIEXPORT jlong JNICALL
Java_sair_sacoms_jni_JNISaComS_math_1strToLong(JNIEnv* env, jclass cls,
    jstring jnumeric) {
    const char* numeric = (*env)->GetStringUTFChars(env, jnumeric, NULL);
    long result = sacoms_math_str_to_long(numeric);
    (*env)->ReleaseStringUTFChars(env, jnumeric, numeric);
    return (jlong)result;
}

/* ================================================================
 *  JNI: CMD
 * ================================================================ */

JNIEXPORT jobjectArray JNICALL
Java_sair_sacoms_jni_JNISaComS_cmd_1exec(JNIEnv* env, jclass cls,
    jstring jcmd, jboolean jisWindows) {
    const char* cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
    SList* list = sacoms_cmd_exec(cmd, jisWindows);
    jobjectArray result = slist_to_jstrarr(env, list);
    (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    slist_free(list);
    return result;
}

/* ================================================================
 *  JNI: Randoms
 * ================================================================ */

JNIEXPORT jint JNICALL
Java_sair_sacoms_jni_JNISaComS_rand_1intRange(JNIEnv* env, jclass cls,
    jint jmin, jint jmax) {
    return sacoms_rand_int_range(jmin, jmax);
}

JNIEXPORT jint JNICALL
Java_sair_sacoms_jni_JNISaComS_rand_1intArray(JNIEnv* env, jclass cls,
    jintArray jarr) {
    if (!jarr) return 0;
    jsize len = (*env)->GetArrayLength(env, jarr);
    if (len == 0) return 0;
    jint* carr = (*env)->GetIntArrayElements(env, jarr, NULL);
    int result = sacoms_rand_int_array(carr, (size_t)len);
    (*env)->ReleaseIntArrayElements(env, jarr, carr, 0);
    return result;
}

/* ================================================================
 *  JNI: Search
 * ================================================================ */

JNIEXPORT jlongArray JNICALL
Java_sair_sacoms_jni_JNISaComS_search_1findAll(JNIEnv* env, jclass cls,
    jstring jhaystack, jstring jneedle) {
    const char* haystack = (*env)->GetStringUTFChars(env, jhaystack, NULL);
    const char* needle   = (*env)->GetStringUTFChars(env, jneedle, NULL);
    size_t count = 0;
    size_t* positions = sacoms_search_find_all(haystack, needle, &count);
    jlongArray result = (*env)->NewLongArray(env, (jsize)count);
    if (positions && count > 0) {
        jlong* jpos = (jlong*)calloc(count, sizeof(jlong));
        for (size_t i = 0; i < count; i++) jpos[i] = (jlong)positions[i];
        (*env)->SetLongArrayRegion(env, result, 0, (jsize)count, jpos);
        free(jpos);
    }
    (*env)->ReleaseStringUTFChars(env, jhaystack, haystack);
    (*env)->ReleaseStringUTFChars(env, jneedle, needle);
    free(positions);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_sair_sacoms_jni_JNISaComS_search_1wildcardMatch(JNIEnv* env, jclass cls,
    jstring jstr, jstring jpattern) {
    const char* str     = (*env)->GetStringUTFChars(env, jstr, NULL);
    const char* pattern = (*env)->GetStringUTFChars(env, jpattern, NULL);
    int result = sacoms_search_wildcard_match(str, pattern);
    (*env)->ReleaseStringUTFChars(env, jstr, str);
    (*env)->ReleaseStringUTFChars(env, jpattern, pattern);
    return (jboolean)result;
}

JNIEXPORT jobjectArray JNICALL
Java_sair_sacoms_jni_JNISaComS_search_1bracketMatch(JNIEnv* env, jclass cls,
    jstring jstr) {
    const char* str = (*env)->GetStringUTFChars(env, jstr, NULL);
    size_t* pairs = NULL;
    size_t count  = 0;
    int ret = sacoms_search_bracket_match(str, &pairs, &count);
    (*env)->ReleaseStringUTFChars(env, jstr, str);

    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    if (ret != 0 || count == 0) {
        jobjectArray empty = (*env)->NewObjectArray(env, 0, strClass, NULL);
        (*env)->DeleteLocalRef(env, strClass);
        return empty;
    }

    /* Return as String[] where each element is "openPos,closePos" */
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)count, strClass, NULL);
    char buf[64];
    for (size_t i = 0; i < count; i++) {
        snprintf(buf, sizeof(buf), "%zu,%zu", pairs[i*2], pairs[i*2+1]);
        jstring js = (*env)->NewStringUTF(env, buf);
        (*env)->SetObjectArrayElement(env, result, (jsize)i, js);
        (*env)->DeleteLocalRef(env, js);
    }
    (*env)->DeleteLocalRef(env, strClass);
    free(pairs);
    return result;
}
