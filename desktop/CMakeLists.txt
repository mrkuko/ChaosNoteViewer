cmake_minimum_required(VERSION 3.21)
project(ChaosNoteViewer VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Widgets Quick QuickControls2 Sql)
qt_standard_project_setup(REQUIRES 6.5)
qt_policy(SET QTP0004 NEW)

# shared/Migrations.js and shared/FirebaseConfig.js are generated, see generate.py (needs Python 3)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
execute_process(COMMAND ${Python3_EXECUTABLE} -I ${CMAKE_CURRENT_SOURCE_DIR}/generate.py
                RESULT_VARIABLE GENERATE_RESULT)
if(NOT GENERATE_RESULT EQUAL 0)
    message(FATAL_ERROR "generate.py failed")
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    ${CMAKE_CURRENT_SOURCE_DIR}/generate.py
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
    shared/Markdown.js
    shared/Migrations.js
    shared/NoteStore.js
    shared/QrCode.js
    shared/SetupCode.js
    shared/NoteBody.qml
    shared/QrImage.qml
    shared/Sync.js)
foreach(f ${SHARED_FILES})
    get_filename_component(name ${f} NAME)
    set_source_files_properties(${f} PROPERTIES QT_RESOURCE_ALIAS ${name})
endforeach()

# the app's own icons (black, recolored by the UI), at icons/<name>.svg in the module
file(GLOB SHARED_ICONS ${CMAKE_CURRENT_SOURCE_DIR}/shared/icons/*.svg ${CMAKE_CURRENT_SOURCE_DIR}/shared/icons/*.png)
foreach(f ${SHARED_ICONS})
    get_filename_component(name ${f} NAME)
    set_source_files_properties(${f} PROPERTIES QT_RESOURCE_ALIAS icons/${name})
endforeach()

# the app logo, embedded as a Qt resource (tray + window icon, see src/main.cpp)
set_source_files_properties(assets/chaosnoteviewer.png PROPERTIES QT_RESOURCE_ALIAS app-icon.png)

# WIN32 = Windows GUI app (no console window); ignored on Linux
qt_add_executable(chaosnoteviewer WIN32 src/main.cpp)

# the .exe's own icon (Explorer, taskbar, notification area)
if(WIN32)
    enable_language(RC)
    target_sources(chaosnoteviewer PRIVATE assets/chaosnoteviewer.rc)
endif()

# Theme.qml is a singleton (colors and sizes of the tray UI)
set_source_files_properties(qml/Theme.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)

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
        qml/CalendarPage.qml
        qml/ConfirmBox.qml
        qml/DeadlineCalendar.qml
        qml/DetailPage.qml
        qml/EditPage.qml
        qml/NavButton.qml
        qml/NotesList.qml
        qml/SelectableText.qml
        qml/SettingsPage.qml
        qml/TextButton.qml
        qml/Theme.qml
        qml/TrayController.qml
        ${SHARED_FILES}
    RESOURCES
        ${SHARED_ICONS}
        assets/chaosnoteviewer.png
)

target_include_directories(chaosnoteviewer PRIVATE src)
target_link_libraries(chaosnoteviewer PRIVATE Qt6::Widgets Qt6::Quick Qt6::QuickControls2 Qt6::Sql)
