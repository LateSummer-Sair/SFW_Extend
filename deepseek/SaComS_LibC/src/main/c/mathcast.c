#include "sacoms_libc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <math.h>

/* ---- Constant tables ---- */

/* Small Chinese digits: 零一二三四五六七八九 */
static const char* SMALL_NUM[] = {
    "\xe9\x9b\xb6",  /* 零 */
    "\xe4\xb8\x80",  /* 一 */
    "\xe4\xba\x8c",  /* 二 */
    "\xe4\xb8\x89",  /* 三 */
    "\xe5\x9b\x9b",  /* 四 */
    "\xe4\xba\x94",  /* 五 */
    "\xe5\x85\xad",  /* 六 */
    "\xe4\xb8\x83",  /* 七 */
    "\xe5\x85\xab",  /* 八 */
    "\xe4\xb9\x9d",  /* 九 */
};

/* Big Chinese digits: 零壹贰叁肆伍陆柒捌玖 */
static const char* BIG_NUM[] = {
    "\xe9\x9b\xb6",  /* 零 */
    "\xe5\xa3\xb9",  /* 壹 */
    "\xe8\xb4\xb0",  /* 贰 */
    "\xe5\x8f\x81",  /* 叁 */
    "\xe8\x82\x86",  /* 肆 */
    "\xe4\xbc\x8d",  /* 伍 */
    "\xe9\x99\x86",  /* 陆 */
    "\xe6\x9f\x92",  /* 柒 */
    "\xe6\x8d\x8c",  /* 捌 */
    "\xe7\x8e\x96",  /* 玖 */
};

/* Chinese unit chars: 个十百千 */
static const char* UNITS_SMALL[] = {
    "",           /* 个 (implied) */
    "\xe5\x8d\x81",  /* 十 */
    "\xe7\x99\xbe",  /* 百 */
    "\xe5\x8d\x83",  /* 千 */
};

static const char* UNITS_BIG[] = {
    "",           /* 个 */
    "\xe6\x8b\xbe",  /* 拾 */
    "\xe4\xbd\xb0",  /* 佰 */
    "\xe4\xbb\x9f",  /* 仟 */
};

/* Large units: 万 亿 兆 */
static const char* LARGE_UNITS[] = {
    "\xe4\xb8\x87",  /* 万 */
    "\xe4\xba\xbf",  /* 亿 */
    "\xe5\x85\x86",  /* 兆 */
};

/* Reverse lookup tables for Chinese → number */
typedef struct {
    const char* str;
    int         value;
} CNNumEntry;

/* Map single Chinese digit chars to values */
static int cn_char_to_val(const char* s) {
    for (int i = 0; i < 10; i++) {
        if (strcmp(s, SMALL_NUM[i]) == 0 || strcmp(s, BIG_NUM[i]) == 0) return i;
    }
    return -1;
}

/* Check if a UTF-8 string matches a given multi-byte sequence */
static int utf8_match(const char* s, const char* cn) {
    return strcmp(s, cn) == 0;
}

/* Get UTF-8 character byte length from first byte */
static int utf8_char_len(unsigned char c) {
    if (c < 0x80) return 1;
    if ((c & 0xE0) == 0xC0) return 2;
    if ((c & 0xF0) == 0xE0) return 3;
    if ((c & 0xF8) == 0xF0) return 4;
    return 1;
}

/* Extract one UTF-8 character as a temporary string.
 * Caller must free the returned buffer. */
static char* utf8_next_char(const char* s, int* consumed) {
    if (!s || !*s) { if (consumed) *consumed = 0; return NULL; }
    int len = utf8_char_len((unsigned char)*s);
    char* ch = (char*)malloc(len + 1);
    if (!ch) { if (consumed) *consumed = 0; return NULL; }
    memcpy(ch, s, len);
    ch[len] = '\0';
    if (consumed) *consumed = len;
    return ch;
}

/* ---- Chinese → Number ---- */

/* Check if ch is a unit indicator: 十百千万亿兆拾佰仟 */
static int cn_is_unit(const char* ch) {
    const char* units[] = {
        "\xe5\x8d\x81", "\xe7\x99\xbe", "\xe5\x8d\x83",  /* 十 百 千 */
        "\xe4\xb8\x87", "\xe4\xba\xbf", "\xe5\x85\x86",  /* 万 亿 兆 */
        "\xe6\x8b\xbe", "\xe4\xbd\xb0", "\xe4\xbb\x9f",  /* 拾 佰 仟 */
        NULL
    };
    for (int i = 0; units[i]; i++) {
        if (strcmp(ch, units[i]) == 0) return 1;
    }
    return 0;
}

static long cn_unit_value(const char* ch) {
    if (utf8_match(ch, "\xe5\x8d\x81") || utf8_match(ch, "\xe6\x8b\xbe")) return 10;    /* 十/拾 */
    if (utf8_match(ch, "\xe7\x99\xbe") || utf8_match(ch, "\xe4\xbd\xb0")) return 100;    /* 百/佰 */
    if (utf8_match(ch, "\xe5\x8d\x83") || utf8_match(ch, "\xe4\xbb\x9f")) return 1000;   /* 千/仟 */
    if (utf8_match(ch, "\xe4\xb8\x87")) return 10000;       /* 万 */
    if (utf8_match(ch, "\xe4\xba\xbf")) return 100000000;   /* 亿 */
    if (utf8_match(ch, "\xe5\x85\x86")) return 1000000000000LL; /* 兆 */
    return 0;
}

static int cn_is_point(const char* ch) {
    return utf8_match(ch, "\xe7\x82\xb9");  /* 点 */
}

long sacoms_math_chinese_to_long(const char* chinese) {
    if (!chinese || !*chinese) return 0;

    long result = 0;
    long current = 0;
    long section = 0;
    int consumed = 0;

    const char* p = chinese;
    while (*p) {
        char* ch = utf8_next_char(p, &consumed);
        if (!ch) break;
        p += consumed;

        int val = cn_char_to_val(ch);
        if (val >= 0) {
            current = val;
            free(ch);
            continue;
        }

        if (cn_is_unit(ch)) {
            long uv = cn_unit_value(ch);
            if (uv >= 10000) {
                /* large unit: 万/亿/兆 */
                section = (section + current) * uv;
                result += section;
                section = 0;
                current = 0;
            } else {
                /* small unit: 十/百/千 */
                if (current == 0) current = 1; /* e.g. "十" = 10 */
                section += current * uv;
                current = 0;
            }
        } else if (cn_is_point(ch)) {
            /* stop parsing at decimal point for long */
            free(ch);
            break;
        } else if (strcmp(ch, "\xe9\x9b\xb6") == 0) {
            /* 零 — handled implicitly, just skip the current value */
            current = 0;
        }
        free(ch);
    }
    result += section + current;
    return result;
}

/* ---- Number → Chinese ---- */

static void long_to_cn_section(long val, const char** digitTable,
                                const char** unitTable, char* buf, size_t* pos) {
    if (val == 0) return;

    int digits[4] = {0};
    int di = 0;
    while (val > 0 && di < 4) {
        digits[di++] = (int)(val % 10);
        val /= 10;
    }

    int needZero = 0;
    for (int i = di - 1; i >= 0; i--) {
        if (digits[i] != 0) {
            if (needZero) {
                strcat(buf, digitTable[0]); /* 零 */
                needZero = 0;
            }
            strcat(buf, digitTable[digits[i]]);
            if (i > 0) strcat(buf, unitTable[i]);
        } else {
            needZero = 1;
        }
    }
}

char* sacoms_math_long_to_chinese(long value, int upperCase) {
    const char** digitTable = upperCase ? BIG_NUM : SMALL_NUM;
    const char** unitTable  = upperCase ? UNITS_BIG : UNITS_SMALL;

    if (value == 0) {
        return _strdup(digitTable[0]);
    }

    int negative = 0;
    if (value < 0) {
        negative = 1;
        value = -value;
    }

    /* max possible output: ~256 chars is plenty */
    char* result = (char*)calloc(512, 1);
    if (!result) return NULL;

    if (negative) strcat(result, "\xe8\xb4\x9f"); /* 负 */

    long sections[4] = {0};
    long v = value;
    int si = 0;
    while (v > 0 && si < 4) {
        sections[si++] = v % 10000;
        v /= 10000;
    }

    for (int i = si - 1; i >= 0; i--) {
        if (sections[i] == 0) {
            /* only output 零 if there was content before and after */
            if (i < si - 1 && (i > 0 && sections[i-1] > 0)) {
                strcat(result, digitTable[0]);
            }
            continue;
        }
        /* if this section < 1000 and it's not the first section, add 零 */
        if (i < si - 1 && sections[i] < 1000) {
            strcat(result, digitTable[0]);
        }
        size_t pos = 0;
        long_to_cn_section(sections[i], digitTable, unitTable, result, &pos);
        if (i > 0) strcat(result, LARGE_UNITS[i-1]);
    }

    return result;
}

char* sacoms_math_double_to_chinese(double value, int upperCase) {
    const char** digitTable = upperCase ? BIG_NUM : SMALL_NUM;

    int negative = 0;
    if (value < 0) { negative = 1; value = -value; }

    long intPart = (long)value;
    double fracPart = value - intPart;

    char* intStr = sacoms_math_long_to_chinese(intPart, upperCase);
    if (!intStr) return NULL;

    size_t resultSize = strlen(intStr) + 200;
    char* result = (char*)calloc(resultSize, 1);
    if (!result) { free(intStr); return NULL; }

    if (negative) strcat(result, "\xe8\xb4\x9f"); /* 负 */

    strcat(result, intStr);
    free(intStr);

    /* handle fractional part */
    if (fracPart > 0.00000001) {
        strcat(result, "\xe7\x82\xb9"); /* 点 */
        for (int i = 0; i < 8; i++) {
            fracPart *= 10;
            int digit = (int)fracPart;
            strcat(result, digitTable[digit]);
            fracPart -= digit;
            if (fracPart < 0.00000001) break;
        }
    }

    return result;
}

double sacoms_math_chinese_to_double(const char* chinese) {
    if (!chinese || !*chinese) return 0.0;

    long intPart = 0;
    double fracPart = 0.0;
    int inFraction = 0;
    double fracDiv = 10.0;

    const char* p = chinese;
    int consumed = 0;

    /* collect the integer part until '点' */
    char* intStr = (char*)calloc(strlen(chinese) + 1, 1);
    char* fracStr = (char*)calloc(strlen(chinese) + 1, 1);
    size_t ip = 0, fp = 0;

    while (*p) {
        char* ch = utf8_next_char(p, &consumed);
        if (!ch) break;
        p += consumed;

        if (cn_is_point(ch)) {
            inFraction = 1;
            free(ch);
            continue;
        }
        if (inFraction) {
            int dl = utf8_char_len((unsigned char)*p ? *(p - consumed + 0) : 0);
            fracStr[fp] = (char)(cn_char_to_val(ch) + '0');
            fp++;
        } else {
            intStr[ip++] = *p; /* approximate */
        }
        free(ch);
    }

    intPart = sacoms_math_chinese_to_long(chinese);

    /* compute fractional part */
    if (fp > 0) {
        double div = 0.1;
        for (size_t i = 0; i < fp; i++) {
            if (fracStr[i] >= '0' && fracStr[i] <= '9')
                fracPart += (fracStr[i] - '0') * div;
            div *= 0.1;
        }
    }

    free(intStr);
    free(fracStr);

    double result = (double)intPart + fracPart;
    /* detect negative */
    const char* negCheck = "\xe8\xb4\x9f"; /* 负 */
    if (chinese && strncmp(chinese, negCheck, 3) == 0) result = -result;

    return result;
}

long sacoms_math_str_to_long(const char* numeric) {
    if (!numeric) return 0;
    return atol(numeric);
}
