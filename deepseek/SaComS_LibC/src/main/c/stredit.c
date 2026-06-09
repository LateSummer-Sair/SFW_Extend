#include "sacoms_libc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---- StrEdit API ---- */

char** sacoms_str_cast_arr(void** objArr, size_t len, size_t* outLen) {
    if (!objArr || len == 0) {
        if (outLen) *outLen = 0;
        return (char**)calloc(1, sizeof(char*));
    }
    /* count non-null */
    size_t valid = 0;
    for (size_t i = 0; i < len; i++) {
        if (objArr[i]) valid++;
    }
    char** result = (char**)calloc(valid, sizeof(char*));
    if (!result) { if (outLen) *outLen = 0; return NULL; }

    size_t idx = 0;
    for (size_t i = 0; i < len; i++) {
        if (objArr[i]) {
            result[idx++] = _strdup((const char*)objArr[i]);
        }
    }
    if (outLen) *outLen = valid;
    return result;
}

char* sacoms_str_join(char** arr, size_t len, int withNewline) {
    if (!arr || len == 0) {
        char* empty = (char*)malloc(1);
        empty[0] = '\0';
        return empty;
    }

    /* calculate total length */
    size_t totalLen = 0;
    const char* sep = withNewline ? "\r\n" : "";
    size_t sepLen = withNewline ? 2 : 0;

    for (size_t i = 0; i < len; i++) {
        if (arr[i]) totalLen += strlen(arr[i]);
        if (i < len - 1) totalLen += sepLen;
    }

    char* result = (char*)malloc(totalLen + 1);
    if (!result) return NULL;
    result[0] = '\0';

    for (size_t i = 0; i < len; i++) {
        if (arr[i]) strcat(result, arr[i]);
        if (i < len - 1 && withNewline) strcat(result, sep);
    }
    return result;
}

char** sacoms_str_split_chars(const char* str, size_t* outLen) {
    if (!str) {
        if (outLen) *outLen = 0;
        return (char**)calloc(1, sizeof(char*));
    }
    size_t len = strlen(str);
    if (outLen) *outLen = len;

    char** result = (char**)calloc(len, sizeof(char*));
    if (!result) return NULL;

    for (size_t i = 0; i < len; i++) {
        /* each character becomes its own 1-char + null string */
        result[i] = (char*)malloc(2);
        result[i][0] = str[i];
        result[i][1] = '\0';
    }
    return result;
}

SList* sacoms_str_remove(SList* src, const char* pattern, int lineMode) {
    SList* out = slist_create();
    if (!out || !src) return out;

    size_t patternLen = pattern ? strlen(pattern) : 0;

    for (size_t i = 0; i < src->length; i++) {
        const char* line = src->entries[i];
        if (!line) { slist_add(out, ""); continue; }

        if (lineMode) {
            /* lineMode: skip if whole line equals pattern */
            if (pattern && strcmp(line, pattern) == 0) continue;
            slist_add(out, line);
        } else {
            /* oneMode: remove pattern substring from line */
            if (!pattern || patternLen == 0) {
                slist_add(out, line);
                continue;
            }
            /* build new string removing all occurrences of pattern */
            size_t lineLen = strlen(line);
            char* newLine = (char*)malloc(lineLen + 1);
            if (!newLine) { slist_add(out, line); continue; }

            size_t wi = 0;
            for (size_t ci = 0; ci < lineLen; ci++) {
                /* check if pattern matches at current position */
                if (ci + patternLen <= lineLen &&
                    memcmp(line + ci, pattern, patternLen) == 0) {
                    ci += patternLen - 1; /* skip pattern */
                } else {
                    newLine[wi++] = line[ci];
                }
            }
            newLine[wi] = '\0';
            slist_add(out, newLine);
            free(newLine);
        }
    }
    return out;
}

char* sacoms_str_replace(const char* src, const char* old, const char* newStr) {
    if (!src) {
        char* empty = (char*)malloc(1);
        empty[0] = '\0';
        return empty;
    }
    if (!old || !newStr || strlen(old) == 0) {
        return _strdup(src);
    }

    size_t srcLen    = strlen(src);
    size_t oldLen    = strlen(old);
    size_t newLen    = strlen(newStr);

    /* count occurrences */
    size_t count = 0;
    for (size_t i = 0; i + oldLen <= srcLen; i++) {
        if (memcmp(src + i, old, oldLen) == 0) {
            count++;
            i += oldLen - 1;
        }
    }

    size_t resultLen = srcLen + count * (newLen - oldLen) + 1;
    char* result = (char*)malloc(resultLen);
    if (!result) return NULL;

    size_t wi = 0;
    for (size_t i = 0; i < srcLen; ) {
        if (i + oldLen <= srcLen && memcmp(src + i, old, oldLen) == 0) {
            memcpy(result + wi, newStr, newLen);
            wi += newLen;
            i  += oldLen;
        } else {
            result[wi++] = src[i++];
        }
    }
    result[wi] = '\0';
    return result;
}
