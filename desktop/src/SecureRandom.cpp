#include "SecureRandom.h"

#include <QByteArray>
#include <QRandomGenerator>

QString SecureRandom::hex(int n) const
{
    if (n <= 0 || n > 4096)
        return {};
    QByteArray bytes;
    bytes.reserve(n + 4);
    while (bytes.size() < n) {
        const quint32 word = QRandomGenerator::system()->generate();
        bytes.append(reinterpret_cast<const char *>(&word), 4);
    }
    bytes.truncate(n);
    return QString::fromLatin1(bytes.toHex());
}
