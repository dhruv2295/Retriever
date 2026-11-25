// SQLDelight's iOS driver (SQLiter) wraps every sqlite3 function, but iOS's libsqlite3 exports neither of these two
// (Apple leaves extension loading out). A normal link dead-strips the unused wrappers; Xcode Previews link without
// stripping and stop on the missing symbols, so no preview renders. Retriever never loads SQLite extensions: these
// refuse with SQLITE_ERROR, and only in debug builds, where previews run.
#if DEBUG

int sqlite3_enable_load_extension(void *db, int onoff);
int sqlite3_load_extension(void *db, const char *file, const char *entry, char **error);

int sqlite3_enable_load_extension(void *db, int onoff) {
    (void)db;
    (void)onoff;
    return 1;
}

int sqlite3_load_extension(void *db, const char *file, const char *entry, char **error) {
    (void)db;
    (void)file;
    (void)entry;
    if (error) *error = 0;
    return 1;
}

#endif
