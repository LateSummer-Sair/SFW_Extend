cmake_minimum_required(VERSION 3.10)
project(SaComS_LibC C)

set(CMAKE_C_STANDARD 11)

# JNI: point to JDK in SairFrameWork
set(JAVA_HOME "D:/APPs/SairFrameWork/8x64")
set(JNI_INCLUDE_DIR "${JAVA_HOME}/include")
set(JNI_PLATFORM_INCLUDE_DIR "${JAVA_HOME}/include/win32")

# Gather sources
file(GLOB SOURCES "src/main/c/*.c")

# Build shared library
add_library(SaComS_LibC SHARED ${SOURCES})

target_include_directories(SaComS_LibC PRIVATE
    ${JNI_INCLUDE_DIR}
    ${JNI_PLATFORM_INCLUDE_DIR}
)

# Output to lib/ directory
set_target_properties(SaComS_LibC PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/lib"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/lib"
)

# Windows: .dll / Linux: .so
if(WIN32)
    set_target_properties(SaComS_LibC PROPERTIES SUFFIX ".dll")
endif()
