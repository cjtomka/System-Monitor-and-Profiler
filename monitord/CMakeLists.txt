cmake_minimum_required(VERSION 3.20)
project(monitord C)
set(CMAKE_C_STANDARD 11)

option(ENABLE_ASAN "Enable AddressSanitizer" OFF)

if(ENABLE_ASAN)
    if(MSVC)
        # MSVC requires /fsanitize=address and debug info
        add_compile_options(/fsanitize=address /Zi)
        add_link_options(/INCREMENTAL:NO)
    else()
        # GCC and Clang
        add_compile_options(-fsanitize=address -fno-omit-frame-pointer -g)
        add_link_options(-fsanitize=address)
    endif()
endif()

add_library(monitor_core STATIC
    src/sampler.c src/ringbuf.c src/log.c src/proto.c src/server.c src/sockpath.c
    src/collectors/win/pdh_session.c
    src/collectors/win/cpu_pdh.c
    src/collectors/win/mem_pdh.c
    src/collectors/win/disk_pdh.c
    src/collectors/win/proc_pdh.c)
target_include_directories(monitor_core PUBLIC src)
target_link_libraries(monitor_core PUBLIC pdh psapi ws2_32)
target_compile_definitions(monitor_core PUBLIC _CRT_SECURE_NO_WARNINGS WIN32_LEAN_AND_MEAN)

add_executable(monitord src/main.c)                 # the daemon
target_link_libraries(monitord PRIVATE monitor_core)

add_executable(selftest tests/selftest.c)           # your old harness, moved from src/main.c
target_link_libraries(selftest PRIVATE monitor_core)

add_executable(monctl tools/monctl.c src/sockpath.c)
target_include_directories(monctl PRIVATE src)
target_link_libraries(monctl PRIVATE ws2_32)
target_compile_definitions(monctl PRIVATE _CRT_SECURE_NO_WARNINGS WIN32_LEAN_AND_MEAN)

enable_testing()
add_executable(test_proto tests/test_proto.c src/proto.c)
target_include_directories(test_proto PRIVATE src)
target_compile_definitions(test_proto PRIVATE _CRT_SECURE_NO_WARNINGS)
add_test(NAME proto COMMAND test_proto)

if (MSVC)
    foreach (t monitor_core monitord selftest monctl test_proto)
        target_compile_options(${t} PRIVATE /W4)
    endforeach()
endif()