cmake_minimum_required(VERSION 3.4.1)
project(delivra_node)

add_library(native-lib SHARED src/main/cpp/native-lib.cpp)

# Include node's header files
include_directories(src/main/cpp/include/node/)
             
add_library(libnode SHARED IMPORTED)

set_target_properties(libnode
                       PROPERTIES IMPORTED_LOCATION
                       ${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libnode.so)

find_library(log-lib log)

target_link_libraries(native-lib libnode ${log-lib})
