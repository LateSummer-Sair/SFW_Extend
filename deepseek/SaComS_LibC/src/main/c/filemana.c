#include "sacoms_libc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#ifdef _WIN32
#include <windows.h>
#define unlink _unlink
#else
#include <unistd.h>
#endif

/* ---- SList helpers ---- */

SList* slist_create(void) {
    SList* list = (SList*)calloc(1, sizeof(SList));
    if (!list) return NULL;
    list->capacity = 16;
    list->entries  = (char**)calloc(list->capacity, sizeof(char*));
    if (!list->entries) { free(list); return NULL; }
    return list;
}

void slist_add(SList* list, const char* str) {
    if (!list) return;
    if (list->length >= list->capacity) {
        list->capacity *= 2;
        list->entries = (char**)realloc(list->entries, list->capacity * sizeof(char*));
    }
    list->entries[list->length++] = str ? _strdup(str) : _strdup("");
}

void slist_free(SList* list) {
    if (!list) return;
    for (size_t i = 0; i < list->length; i++) {
        free(list->entries[i]);
    }
    free(list->entries);
    free(list);
}

/* ---- Internal helpers ---- */

#ifdef _WIN32
static char* widen_to_utf8(const wchar_t* wstr) {
    int len = WideCharToMultiByte(CP_UTF8, 0, wstr, -1, NULL, 0, NULL, NULL);
    if (len <= 0) return NULL;
    char* buf = (char*)malloc(len);
    WideCharToMultiByte(CP_UTF8, 0, wstr, -1, buf, len, NULL, NULL);
    return buf;
}
#endif

/* ---- FileMana API ---- */

SList* sacoms_file_read_lines(const char* filePath, const char* encoding) {
    if (!filePath) return NULL;

    FILE* fp = fopen(filePath, "rb");
    if (!fp) return NULL;

    SList* list = slist_create();
    if (!list) { fclose(fp); return NULL; }

    /* determine buffer size */
    fseek(fp, 0, SEEK_END);
    long fsize = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    if (fsize <= 0) { fclose(fp); return list; }

    char* raw = (char*)malloc(fsize + 1);
    if (!raw) { fclose(fp); slist_free(list); return NULL; }
    fread(raw, 1, fsize, fp);
    raw[fsize] = '\0';
    fclose(fp);

    /* split by \n and \r\n */
    char* lineStart = raw;
    for (long i = 0; i < fsize; i++) {
        if (raw[i] == '\n' || raw[i] == '\r') {
            char saved = raw[i];
            raw[i] = '\0';
            /* skip leading \r if we saw \r\n */
            if (lineStart[0] != '\0' || (saved == '\n' && i > 0 && raw[i-1] == '\r')) {
                /* trim trailing \r */
                size_t lineLen = strlen(lineStart);
                if (lineLen > 0 && lineStart[lineLen-1] == '\r')
                    lineStart[lineLen-1] = '\0';
                slist_add(list, lineStart);
            }
            lineStart = raw + i + 1;
        }
    }
    /* handle last line (no trailing newline) */
    if (*lineStart != '\0') {
        slist_add(list, lineStart);
    }
    free(raw);
    return list;
}

int sacoms_file_write_lines(const char* filePath, SList* lines, int withNewline) {
    if (!filePath || !lines) return -1;
    FILE* fp = fopen(filePath, "wb");
    if (!fp) return -1;

    int written = 0;
    for (size_t i = 0; i < lines->length; i++) {
        if (lines->entries[i]) {
            fputs(lines->entries[i], fp);
        }
        if (withNewline) {
            fputs("\r\n", fp);
        }
        written++;
    }
    fclose(fp);
    return written;
}

int sacoms_file_append_lines(const char* filePath, SList* lines, int withNewline) {
    if (!filePath || !lines) return -1;
    FILE* fp = fopen(filePath, "ab");
    if (!fp) return -1;

    int written = 0;
    for (size_t i = 0; i < lines->length; i++) {
        if (lines->entries[i]) {
            fputs(lines->entries[i], fp);
        }
        if (withNewline) {
            fputs("\r\n", fp);
        }
        written++;
    }
    fclose(fp);
    return written;
}

int sacoms_file_delete(const char* path) {
    if (!path) return -1;
    if (remove(path) == 0) return 0;
    return -1;
}

int sacoms_file_copy(const char* src, const char* dst) {
    if (!src || !dst) return -1;
    FILE* fin = fopen(src, "rb");
    if (!fin) return -1;
    FILE* fout = fopen(dst, "wb");
    if (!fout) { fclose(fin); return -1; }

    char buf[8192];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), fin)) > 0) {
        fwrite(buf, 1, n, fout);
    }
    fclose(fin);
    fclose(fout);
    return 0;
}

int sacoms_file_exists(const char* path) {
    if (!path) return 0;
    FILE* fp = fopen(path, "rb");
    if (fp) { fclose(fp); return 1; }
    return 0;
}
