cmake_minimum_required(VERSION 3.21)
project(ChaosNoteViewer VERSION 0.0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Widgets Quick QuickControls2)
qt_standard_project_setup(REQUIRES 6.5)
qt_policy(SET QTP0004 NEW)

qt_add_executable(chaosnoteviewer src/main.cpp)

qt_add_qml_module(chaosnoteviewer
    URI ChaosNotes
    VERSION 1.0
    SOURCES
        src/NoteModel.h
        src/NoteModel.cpp
    QML_FILES
        qml/Main.qml
        qml/NoteDelegate.qml
)

target_include_directories(chaosnoteviewer PRIVATE src)
target_link_libraries(chaosnoteviewer PRIVATE Qt6::Widgets Qt6::Quick Qt6::QuickControls2)
