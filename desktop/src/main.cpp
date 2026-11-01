#include <QApplication>
#include <QElapsedTimer>
#include <QIcon>
#include <QMenu>
#include <QPainter>
#include <QPixmap>
#include <QQmlApplicationEngine>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QScreen>
#include <QSystemTrayIcon>

// Placeholder tray icon until real artwork exists.
static QIcon makeTrayIcon()
{
    QPixmap pm(64, 64);
    pm.fill(Qt::transparent);
    QPainter p(&pm);
    p.setRenderHint(QPainter::Antialiasing);
    p.setBrush(QColor("#3daee9"));
    p.setPen(Qt::NoPen);
    p.drawRoundedRect(QRectF(6, 6, 52, 52), 12, 12);
    p.setPen(QPen(Qt::white, 5, Qt::SolidLine, Qt::RoundCap));
    p.drawLine(20, 24, 44, 24);
    p.drawLine(20, 34, 44, 34);
    p.drawLine(20, 44, 34, 44);
    return QIcon(pm);
}

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    app.setQuitOnLastWindowClosed(false);
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

    QSystemTrayIcon tray(makeTrayIcon());
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
