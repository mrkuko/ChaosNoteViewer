#pragma once

#include <QObject>
#include <QString>
#include <QtQml/qqmlregistration.h>

// Random bytes from the operating system, for the encryption key. QML has no secure random numbers of its
// own; QRandomGenerator::system() is the OS generator (getrandom on Linux, BCryptGenRandom on Windows,
// arc4random on macOS), never a seeded pseudo random generator.
class SecureRandom : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    QML_SINGLETON

public:
    explicit SecureRandom(QObject *parent = nullptr) : QObject(parent) {}

    // n random bytes as 2n lowercase hex digits; an empty string when n is out of range
    Q_INVOKABLE QString hex(int n) const;
};
