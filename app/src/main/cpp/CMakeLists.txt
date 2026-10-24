cmake_minimum_required(VERSION 3.22.1)

project(FunkGatewayAudio LANGUAGES C)

add_library(gsm_codec STATIC
    libgsm/src/add.c
    libgsm/src/code.c
    libgsm/src/debug.c
    libgsm/src/decode.c
    libgsm/src/long_term.c
    libgsm/src/lpc.c
    libgsm/src/preprocess.c
    libgsm/src/rpe.c
    libgsm/src/gsm_destroy.c
    libgsm/src/gsm_decode.c
    libgsm/src/gsm_encode.c
    libgsm/src/gsm_explode.c
    libgsm/src/gsm_implode.c
    libgsm/src/gsm_create.c
    libgsm/src/gsm_print.c
    libgsm/src/gsm_option.c
    libgsm/src/short_term.c
    libgsm/src/table.c
)
target_include_directories(gsm_codec PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libgsm/inc
)
target_compile_definitions(gsm_codec PRIVATE
    WAV49 SASR
)

set_target_properties(gsm_codec PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

add_library(fgw_audio SHARED
    gsm_jni.c
)

target_include_directories(fgw_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libgsm/inc
)

target_link_libraries(fgw_audio PRIVATE
    gsm_codec
)
