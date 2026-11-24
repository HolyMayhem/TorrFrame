cmake_minimum_required(VERSION 3.22)
project(torrframe_dpi C)

set(BYEDPI "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third-party/byedpi")
add_executable(byedpi_exec launcher.c
    ${BYEDPI}/main.c ${BYEDPI}/conev.c ${BYEDPI}/proxy.c ${BYEDPI}/desync.c
    ${BYEDPI}/mpool.c ${BYEDPI}/extend.c ${BYEDPI}/packets.c)
target_compile_definitions(byedpi_exec PRIVATE TORRFRAME_PROXY)
set_source_files_properties(${BYEDPI}/main.c PROPERTIES COMPILE_DEFINITIONS "main=byedpi_main")
target_compile_options(byedpi_exec PRIVATE -O2)
set_target_properties(byedpi_exec PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/bin")

# AGP packages native libraries. Use its library output slot for the PIE
# executable, which Android extracts to the app's executable native directory.
add_library(torrframe_dpi SHARED package_slot.c)
add_dependencies(torrframe_dpi byedpi_exec)
set_property(TARGET torrframe_dpi APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/bin/byedpi_exec")
add_custom_command(TARGET torrframe_dpi POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:byedpi_exec>" "$<TARGET_FILE:torrframe_dpi>")
