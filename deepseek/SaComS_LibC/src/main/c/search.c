#include "sacoms_libc.h"

#include <stdlib.h>
#include <string.h>

/* ---- Search API ---- */

size_t* sacoms_search_find_all(const char* haystack, const char* needle,
                                size_t* outCount) {
    if (!haystack || !needle || !outCount) {
        if (outCount) *outCount = 0;
        return NULL;
    }

    size_t hayLen = strlen(haystack);
    size_t ndlLen = strlen(needle);
    if (ndlLen == 0 || ndlLen > hayLen) {
        *outCount = 0;
        return NULL;
    }

    /* first pass: count matches */
    size_t count = 0;
    const char* p = haystack;
    while ((p = strstr(p, needle)) != NULL) {
        count++;
        p++;
    }

    if (count == 0) {
        *outCount = 0;
        return NULL;
    }

    size_t* positions = (size_t*)malloc(count * sizeof(size_t));
    if (!positions) { *outCount = 0; return NULL; }

    size_t idx = 0;
    p = haystack;
    while ((p = strstr(p, needle)) != NULL && idx < count) {
        positions[idx++] = (size_t)(p - haystack);
        p++;
    }
    *outCount = count;
    return positions;
}

int sacoms_search_wildcard_match(const char* str, const char* pattern) {
    if (!str || !pattern) return 0;

    /* Empty pattern matches empty string */
    if (*pattern == '\0') return (*str == '\0') ? 1 : 0;

    if (*pattern == '*') {
        /* '*' matches zero or more characters */
        if (*(pattern + 1) == '\0') return 1; /* '*' matches everything */
        /* try matching at every position */
        for (const char* s = str; *s; s++) {
            if (sacoms_search_wildcard_match(s, pattern + 1)) return 1;
        }
        return sacoms_search_wildcard_match(str + strlen(str), pattern + 1);
    }

    if (*pattern == '?') {
        /* '?' matches any single character */
        if (*str == '\0') return 0;
        return sacoms_search_wildcard_match(str + 1, pattern + 1);
    }

    /* literal match */
    if (*str == *pattern) {
        return sacoms_search_wildcard_match(str + 1, pattern + 1);
    }

    return 0;
}

int sacoms_search_bracket_match(const char* str, size_t** outPairs,
                                 size_t* outCount) {
    if (!str || !outPairs || !outCount) return -1;

    *outPairs  = NULL;
    *outCount  = 0;

    size_t len = strlen(str);
    /* worst case: every pair of positions */
    size_t* stack = (size_t*)malloc(len * sizeof(size_t));
    if (!stack) return -1;

    size_t stackTop = 0;
    size_t pairCount = 0;
    size_t maxPairs = len / 2;
    size_t* pairs = (size_t*)malloc(maxPairs * 2 * sizeof(size_t));
    if (!pairs) { free(stack); return -1; }

    for (size_t i = 0; i < len; i++) {
        if (str[i] == '{') {
            stack[stackTop++] = i;
        } else if (str[i] == '}') {
            if (stackTop == 0) {
                /* unmatched closing bracket */
                free(pairs);
                free(stack);
                return -1;
            }
            size_t openPos = stack[--stackTop];
            if (pairCount < maxPairs) {
                pairs[pairCount * 2]     = openPos;
                pairs[pairCount * 2 + 1] = i;
                pairCount++;
            }
        }
    }

    free(stack);

    if (stackTop != 0) {
        /* unmatched opening bracket */
        free(pairs);
        return -1;
    }

    if (pairCount > 0) {
        *outPairs = (size_t*)realloc(pairs, pairCount * 2 * sizeof(size_t));
        *outCount = pairCount;
    } else {
        free(pairs);
        *outPairs = NULL;
        *outCount = 0;
    }
    return 0;
}
