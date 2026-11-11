cmake_minimum_required(VERSION 3.21)
project(ChaosNoteViewer VERSION 0.0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Widgets Quick QuickControls2)
qt_standard_project_setup(REQUIRES 6.5)
qt_policy(SET QTP0004 NEW)

# shared/Migrations.js and shared/FirebaseConfig.js are generated, see generate.sh (needs bash and python3)
execute_process(COMMAND bash ${CMAKE_CURRENT_SOURCE_DIR}/generate.sh
                RESULT_VARIABLE GENERATE_RESULT)
if(NOT GENERATE_RESULT EQUAL 0)
    message(FATAL_ERROR "generate.sh failed")
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    ${CMAKE_CURRENT_SOURCE_DIR}/generate.sh
    ${CMAKE_CURRENT_SOURCE_DIR}/../core/storage/migrations
    ${CMAKE_CURRENT_SOURCE_DIR}/firebase.local.json)

# The app logic is shared with the Plasma widget: one copy in shared/, flattened into the module root
# so the files import each other by plain name ("Crypto.js").
set(SHARED_FILES
    shared/AppController.qml
    shared/Auth.js
    shared/Crypto.js
    shared/FirebaseConfig.js
    shared/KeyFlow.js
    shared/Migrations.js
    shared/NoteStore.js
    shared/Sync.js)
foreach(f ${SHARED_FILES})
    get_filename_component(name ${f} NAME)
    set_source_files_properties(${f} PROPERTIES QT_RESOURCE_ALIAS ${name})
endforeach()

qt_add_executable(chaosnoteviewer src/main.cpp)

qt_add_qml_module(chaosnoteviewer
    URI ChaosNotes
    VERSION 1.0
    SOURCES
        src/NoteModel.h
        src/NoteModel.cpp
        src/SecureRandom.h
        src/SecureRandom.cpp
    QML_FILES
        qml/Main.qml
        qml/NoteDelegate.qml
        qml/TrayController.qml
        ${SHARED_FILES}
)

target_include_directories(chaosnoteviewer PRIVATE src)
target_link_libraries(chaosnoteviewer PRIVATE Qt6::Widgets Qt6::Quick Qt6::QuickControls2)
