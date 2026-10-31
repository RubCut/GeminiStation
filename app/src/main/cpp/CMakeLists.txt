cmake_minimum_required(VERSION 3.22.1)
project(gemini_wake_frontend LANGUAGES CXX)
set(FRONTEND ${CMAKE_CURRENT_SOURCE_DIR}/microfrontend)
set(LIB ${FRONTEND}/tensorflow/lite/experimental/microfrontend/lib)
add_library(gemini_wake_frontend SHARED wake_frontend.cc wake_jni.cc
    ${LIB}/kiss_fft_int16.cc ${LIB}/fft.cc ${LIB}/fft_util.cc
    ${LIB}/filterbank.cc ${LIB}/filterbank_util.cc ${LIB}/frontend.cc ${LIB}/frontend_util.cc
    ${LIB}/log_lut.cc ${LIB}/log_scale.cc ${LIB}/log_scale_util.cc
    ${LIB}/noise_reduction.cc ${LIB}/noise_reduction_util.cc
    ${LIB}/pcan_gain_control.cc ${LIB}/pcan_gain_control_util.cc
    ${LIB}/window.cc ${LIB}/window_util.cc ${FRONTEND}/kissfft/kiss_fft.cc
    ${FRONTEND}/kissfft/tools/kiss_fftr.cc)
target_include_directories(gemini_wake_frontend PRIVATE ${FRONTEND} ${FRONTEND}/kissfft)
target_compile_definitions(gemini_wake_frontend PRIVATE FIXED_POINT=16)
target_compile_features(gemini_wake_frontend PRIVATE cxx_std_17)
