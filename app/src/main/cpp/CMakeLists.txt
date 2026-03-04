cmake_minimum_required(VERSION 3.22.1)
project(scrollsnap_native)

add_library(
    scrollsnap_native
    SHARED
    stitch_native.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    scrollsnap_native
    ${log-lib}
    ${jnigraphics-lib}
)
