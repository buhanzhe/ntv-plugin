#include <limits.h>
#include <locale.h>
#include <math.h>
#include <string.h>
#include "api14-math.h"

double log2(double value) {
    if (value <= 0.0 || !isfinite(value)) return log(value);
    int exponent;
    double fraction = frexp(value, &exponent);
    if (fraction < 0.70710678118654752440) {
        fraction *= 2.0;
        --exponent;
    }
    return exponent + log1p(fraction - 1.0) * 1.44269504088896340736;
}

/* The old Bionic setlocale stub does not answer a query for LC_CTYPE. */
char *setlocale(int category, const char *name) {
    static char c_locale[] = "C";
    (void)category;
    if (!name || !*name || strcmp(name, "C") == 0 || strcmp(name, "POSIX") == 0) return c_locale;
    return NULL;
}

/* Android 4.0 supplies the C locale without the localeconv entry point. */
struct lconv *localeconv(void) {
    static char empty[] = "";
    static char point[] = ".";
    static struct lconv info = {
        .decimal_point = point,
        .thousands_sep = empty,
        .grouping = empty,
        .int_curr_symbol = empty,
        .currency_symbol = empty,
        .mon_decimal_point = empty,
        .mon_thousands_sep = empty,
        .mon_grouping = empty,
        .positive_sign = empty,
        .negative_sign = empty,
        .int_frac_digits = CHAR_MAX,
        .frac_digits = CHAR_MAX,
        .p_cs_precedes = CHAR_MAX,
        .p_sep_by_space = CHAR_MAX,
        .n_cs_precedes = CHAR_MAX,
        .n_sep_by_space = CHAR_MAX,
        .p_sign_posn = CHAR_MAX,
        .n_sign_posn = CHAR_MAX,
        .int_p_cs_precedes = CHAR_MAX,
        .int_p_sep_by_space = CHAR_MAX,
        .int_n_cs_precedes = CHAR_MAX,
        .int_n_sep_by_space = CHAR_MAX,
        .int_p_sign_posn = CHAR_MAX,
        .int_n_sign_posn = CHAR_MAX,
    };
    return &info;
}
