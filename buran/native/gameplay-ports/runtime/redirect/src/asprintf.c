#include <stdarg.h>

extern int vasprintf(char **out, const char *format, va_list args);

__attribute__((visibility("hidden")))
int gameplay_asprintf(char **out, const char *format, ...) {
    va_list args;
    va_start(args, format);
    int result = vasprintf(out, format, args);
    va_end(args);
    return result;
}
