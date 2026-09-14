# LiteRT C++ SDK 2.1.0
cmake_minimum_required(VERSION 3.22.1)
project(litert_cc_sdk)

# Include directories
set(LITERT_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")

# Библиотеки не лежат в репозитории: их распаковывает из AAR задача
# unpackLiteRtJni и передаёт путь через -DLITERT_JNI_DIR.
if(NOT DEFINED LITERT_JNI_DIR)
    message(FATAL_ERROR "LITERT_JNI_DIR не задан — запустите сборку через Gradle")
endif()
set(LITERT_LIB_DIR "${LITERT_JNI_DIR}/${ANDROID_ABI}")

if(NOT EXISTS "${LITERT_LIB_DIR}/libLiteRt.so")
    message(FATAL_ERROR "libLiteRt.so для ${ANDROID_ABI} не найдена в ${LITERT_LIB_DIR}")
endif()

# Create imported target for LiteRT core library
add_library(litert_cc_api SHARED IMPORTED GLOBAL)
set_target_properties(litert_cc_api PROPERTIES
    IMPORTED_LOCATION "${LITERT_LIB_DIR}/libLiteRt.so"
)

# Create interface library for include directories
add_library(litert_headers INTERFACE)
target_include_directories(litert_headers INTERFACE "${LITERT_INCLUDE_DIR}")

# Link headers to the main library
target_link_libraries(litert_cc_api INTERFACE litert_headers)

# OpenCL accelerator (optional)
if(EXISTS "${LITERT_LIB_DIR}/libLiteRtOpenClAccelerator.so")
    add_library(litert_opencl SHARED IMPORTED GLOBAL)
    set_target_properties(litert_opencl PROPERTIES
        IMPORTED_LOCATION "${LITERT_LIB_DIR}/libLiteRtOpenClAccelerator.so"
    )
endif()
