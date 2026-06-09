#include "sacoms_libc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif

SList* sacoms_cmd_exec(const char* cmd, int isWindows) {
    if (!cmd) return NULL;

    SList* list = slist_create();
    if (!list) return NULL;

    char tmpFile[256];
    const char* tmpDir = NULL;
#ifdef _WIN32
    tmpDir = getenv("TEMP");
    if (!tmpDir) tmpDir = ".";
#else
    tmpDir = "/tmp";
#endif
    snprintf(tmpFile, sizeof(tmpFile), "%s/sacoms_cmd_tmp_%d.txt",
             tmpDir, (int)(size_t)cmd);

    /* Build the redirect command */
    char fullCmd[4096];
    if (isWindows) {
        snprintf(fullCmd, sizeof(fullCmd), "cmd.exe /c %s > \"%s\" 2>&1", cmd, tmpFile);
    } else {
        snprintf(fullCmd, sizeof(fullCmd), "/bin/sh -c '%s' > \"%s\" 2>&1", cmd, tmpFile);
    }

    int ret = system(fullCmd);
    (void)ret; /* ignore return code — we want output regardless */

    /* Read the temp file */
    FILE* fp = fopen(tmpFile, "rb");
    if (fp) {
        fseek(fp, 0, SEEK_END);
        long fsize = ftell(fp);
        fseek(fp, 0, SEEK_SET);

        if (fsize > 0) {
            char* raw = (char*)malloc(fsize + 1);
            if (raw) {
                fread(raw, 1, fsize, fp);
                raw[fsize] = '\0';

                /* split by newline */
                char* lineStart = raw;
                for (long i = 0; i < fsize; i++) {
                    if (raw[i] == '\n') {
                        raw[i] = '\0';
                        /* trim trailing \r */
                        size_t len = strlen(lineStart);
                        if (len > 0 && lineStart[len-1] == '\r')
                            lineStart[len-1] = '\0';
                        slist_add(list, lineStart);
                        lineStart = raw + i + 1;
                    }
                }
                if (*lineStart) slist_add(list, lineStart);
                free(raw);
            }
        }
        fclose(fp);
    }
    remove(tmpFile);
    return list;
}
