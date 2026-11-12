#include <QApplication>
#include <QElapsedTimer>
#include <QIcon>
#include <QMenu>
#include <QQmlApplicationEngine>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QScreen>
#include <QSystemTrayIcon>

// The app logo (desktop/assets/chaosnoteviewer.png, embedded in the executable). On Linux the icon
// theme's "chaosnoteviewer" icon wins when it is installed (the Plasma widget uses the same name).
static QIcon appIcon()
{
    return QIcon::fromTheme("chaosnoteviewer", QIcon(":/qt/qml/ChaosNotes/app-icon.png"));
}

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    app.setQuitOnLastWindowClosed(false);
    app.setWindowIcon(appIcon());
    QQuickStyle::setStyle("Basic");

    QQmlApplicationEngine engine;
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed,
                     &app, [] { QCoreApplication::exit(-1); }, Qt::QueuedConnection);
    engine.loadFromModule("ChaosNotes", "Main");
    auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().value(0));
    if (!window)
        return -1;

    QElapsedTimer sinceHidden;
    sinceHidden.start();
    QObject::connect(window, &QQuickWindow::visibleChanged, window, [&](bool visible) {
        if (!visible)
            sinceHidden.restart();
    });

    auto showPopup = [&] {
        const QRect area = window->screen()->availableGeometry();
        window->setPosition(area.right() - window->width() - 8,
                            area.bottom() - window->height() - 8);
        window->show();
        window->raise();
        window->requestActivate();
    };

    QSystemTrayIcon tray(appIcon());
    tray.setToolTip("Chaos Note Viewer");

    QMenu menu;
    menu.addAction("Open", showPopup);
    menu.addAction("Quit", &app, &QCoreApplication::quit);
    tray.setContextMenu(&menu);

    QObject::connect(&tray, &QSystemTrayIcon::activated, window,
                     [&](QSystemTrayIcon::ActivationReason reason) {
        if (reason != QSystemTrayIcon::Trigger)
            return;
        if (window->isVisible())
            window->hide();
        else if (sinceHidden.elapsed() > 250) // click that just dismissed it must not reopen it
            showPopup();
    });
    tray.show();

    return app.exec();
}
