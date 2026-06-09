#include "sacoms_libc.h"

#include <stdlib.h>
#include <time.h>

static int s_rand_seeded = 0;

static void ensure_seeded(void) {
    if (!s_rand_seeded) {
        srand((unsigned int)time(NULL));
        s_rand_seeded = 1;
    }
}

int sacoms_rand_int_range(int min, int max) {
    if (min > max) { int t = min; min = max; max = t; }
    ensure_seeded();
    return (rand() % (max - min + 1)) + min;
}

int sacoms_rand_int_array(const int* arr, size_t len) {
    if (!arr || len == 0) return 0;
    ensure_seeded();
    return arr[rand() % len];
}
