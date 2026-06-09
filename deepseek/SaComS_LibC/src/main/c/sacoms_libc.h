#ifndef SACOMS_LIBC_H
#define SACOMS_LIBC_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ================================================================
 *  Data Structures
 * ================================================================ */

/** Dynamic string list — core container for C ↔ Java exchange */
typedef struct {
    char**  entries;   /* array of string pointers          */
    size_t  length;    /* number of entries                 */
    size_t  capacity;  /* allocated slots                   */
} SList;

SList*  slist_create(void);
void    slist_add(SList* list, const char* str);
void    slist_free(SList* list);

/* ================================================================
 *  FileMana — File I/O
 * ================================================================ */

/**
 * Read file line by line into SList.
 * @param filePath  absolute or relative path
 * @param encoding  "utf-8" / "gbk" / NULL for system default
 * @return SList (caller owns, must slist_free); NULL on error
 */
SList*  sacoms_file_read_lines(const char* filePath, const char* encoding);

/**
 * Write SList lines to file (create/overwrite).
 * @return line count written, or -1 on error
 */
int     sacoms_file_write_lines(const char* filePath, SList* lines, int withNewline);

/**
 * Append SList lines to end of file.
 * @return line count written, or -1 on error
 */
int     sacoms_file_append_lines(const char* filePath, SList* lines, int withNewline);

/** Delete a file or empty directory. @return 0 ok, -1 on error */
int     sacoms_file_delete(const char* path);

/** Copy file from src to dst. @return 0 ok, -1 on error */
int     sacoms_file_copy(const char* src, const char* dst);

/** Check whether file exists. @return 1 exists, 0 not */
int     sacoms_file_exists(const char* path);

/* ================================================================
 *  StrEdit — String utilities
 * ================================================================ */

/**
 * Convert Object[] (passed as array of pointers to char*) to String[].
 * @param objArr   array of pointers
 * @param len      number of elements
 * @param outLen   [out] filled with count of non-null entries
 * @return newly allocated char** (caller frees each and the array)
 */
char**  sacoms_str_cast_arr(void** objArr, size_t len, size_t* outLen);

/**
 * Join a char** array into a single string.
 * @param withNewline  1 = insert "\r\n" between entries
 * @return newly allocated string (caller frees)
 */
char*   sacoms_str_join(char** arr, size_t len, int withNewline);

/**
 * Split a string into individual characters (UTF-8 aware, each char as own string).
 * @param outLen  [out] length = strlen(src)
 * @return newly allocated char** (caller frees)
 */
char**  sacoms_str_split_chars(const char* str, size_t* outLen);

/**
 * Remove matching entries from SList.
 * @param pattern    substring to match
 * @param lineMode   1 = delete whole line if equals; 0 = remove pattern chars in-place
 * @return new SList with filtered entries (caller owns)
 */
SList*  sacoms_str_remove(SList* src, const char* pattern, int lineMode);

/**
 * Replace all occurrences of `old` with `newStr` in `src`.
 * @return newly allocated string (caller frees)
 */
char*   sacoms_str_replace(const char* src, const char* old, const char* newStr);

/* ================================================================
 *  MathCast — Number / Chinese-string conversion
 * ================================================================ */

/**
 * Convert Chinese number string to long.
 * e.g. "一百二十三" → 123
 * @return the parsed long, or 0 if parse failed
 */
long    sacoms_math_chinese_to_long(const char* chinese);

/**
 * Convert long to Chinese number string.
 * @param upperCase  1 = uppercase Chinese (壹贰叁), 0 = lowercase (一二三)
 * @return newly allocated string (caller frees)
 */
char*   sacoms_math_long_to_chinese(long value, int upperCase);

/**
 * Convert double to Chinese number string.
 * @param upperCase  1 = uppercase, 0 = lowercase
 * @return newly allocated string (caller frees)
 */
char*   sacoms_math_double_to_chinese(double value, int upperCase);

/**
 * Convert Chinese number string to double.
 * e.g. "一百二十三点四五六" → 123.456
 */
double  sacoms_math_chinese_to_double(const char* chinese);

/**
 * Convert a plain numeric string to long.
 * e.g. "123" → 123
 */
long    sacoms_math_str_to_long(const char* numeric);

/* ================================================================
 *  CMD — Process execution
 * ================================================================ */

/**
 * Execute a shell command and return output line by line.
 * @param cmd       command string to execute
 * @param isWindows 1 = use cmd.exe /c, 0 = use /bin/sh -c
 * @return SList of output lines (caller owns)
 */
SList*  sacoms_cmd_exec(const char* cmd, int isWindows);

/* ================================================================
 *  Randoms — Random utilities
 * ================================================================ */

/**
 * Generate random integer in [min, max] (inclusive).
 */
int     sacoms_rand_int_range(int min, int max);

/**
 * Pick a random element from the given int array.
 * @return the selected value; 0 if array is NULL or len==0
 */
int     sacoms_rand_int_array(const int* arr, size_t len);

/* ================================================================
 *  Search — String search / match
 * ================================================================ */

/**
 * Simple substring search: find all starting positions of `needle` in `haystack`.
 * @param outCount  [out] number of matches found
 * @return newly allocated array of positions (caller frees), NULL if none
 */
size_t* sacoms_search_find_all(const char* haystack, const char* needle, size_t* outCount);

/**
 * Check if `str` matches the simple wildcard `pattern`.
 * Supported: '*' (any chars), '?' (any single char)
 * @return 1 match, 0 not
 */
int     sacoms_search_wildcard_match(const char* str, const char* pattern);

/**
 * Bracket matching helper: given a string with '{' '}', find paired positions.
 * @param outPairs  [out] flat array of [open_pos, close_pos] pairs
 * @param outCount  [out] number of pairs
 * @return 0 on success, -1 on unmatched brackets
 */
int     sacoms_search_bracket_match(const char* str, size_t** outPairs, size_t* outCount);

#ifdef __cplusplus
}
#endif

#endif /* SACOMS_LIBC_H */
