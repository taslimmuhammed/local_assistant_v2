# sqlite-vec as a loadable SQLite extension, for BundledSQLiteDriver.addExtension.
#
# Built as its own shared library rather than into SQLite: the bundled driver ships its own
# SQLite, and the extension reaches it through the sqlite3_api_routines table it is handed when
# loaded, so the headers here only need to be no older than the functions the extension calls.
cmake_minimum_required(VERSION 3.22.1)
project(sqlite_vec C)

add_library(sqlite_vec SHARED sqlite-vec/sqlite-vec.c)
target_include_directories(sqlite_vec PRIVATE sqlite sqlite-vec)
target_compile_options(sqlite_vec PRIVATE -O3)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(sqlite_vec PRIVATE SQLITE_VEC_ENABLE_NEON)
endif()
target_link_libraries(sqlite_vec m)
# 16 KB pages (Android 15+ devices may use them).
target_link_options(sqlite_vec PRIVATE "-Wl,-z,max-page-size=16384")
