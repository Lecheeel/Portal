cmake_minimum_required(VERSION 3.22.1)

project("LocationExt")

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# MumuEmulator crash? if enable x86_64

#find_package(lsplant REQUIRED CONFIG)
find_package(dobby REQUIRED CONFIG)

add_library(locationext SHARED
        main.cpp
        elf_util.cpp
        sensor_hook.cpp)

target_link_libraries(locationext android log)
target_link_libraries(locationext dobby::dobby)
# Keep the deployed library self-contained and hide Dobby/internal symbols from dynsym.
target_link_options(locationext PRIVATE -static-libstdc++ -Wl,--exclude-libs,ALL)
#target_link_libraries(${CMAKE_PROJECT_NAME} lsplant::lsplant)
