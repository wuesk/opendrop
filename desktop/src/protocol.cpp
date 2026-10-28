#include "protocol.h"
#include <QElapsedTimer>
#include <QJsonDocument>
#include <QtEndian>
#include <cmath>
#include <stdexcept>

namespace Drop {
bool validName(const QString &name) {
    if (name.isEmpty() || name == "." || name == ".." || name.toUtf8().size() > 240)
        return false;
    for (const auto ch : name)
        if (ch.unicode() < 32 || ch.unicode() == 127 || ch == '/' || ch == '\\')
            return false;
    return true;
}
QList<FileInfo> parseOffer(const QJsonObject &o, QString &error) {
    QList<FileInfo> files;
    const auto array = o["files"].toArray();
    if (o["protocol"] != "opendrop" || o["version"].toInt() != 1 || o["type"] != "offer" ||
        !o["sender"].isString() || o["sender"].toString().isEmpty() || o["sender"].toString().size() > 80 ||
        array.isEmpty() || array.size() > MaxFiles) {
        error = "Invalid transfer offer";
        return {};
    }
    qint64 total = 0;
    for (const auto &item : array) {
        const auto f = item.toObject();
        const auto n = f["size"].toDouble(-1);
        if (!f["name"].isString() || !validName(f["name"].toString()) || !std::isfinite(n) || n < 0 ||
            n > MaxBytes || n != std::floor(n) || total > MaxBytes - static_cast<qint64>(n)) {
            error = "Invalid filename or file size";
            return {};
        }
        total += static_cast<qint64>(n);
        files.append({f["name"].toString(), static_cast<qint64>(n)});
    }
    return files;
}
QByteArray frame(const QJsonObject &object) {
    const auto json = QJsonDocument(object).toJson(QJsonDocument::Compact);
    QByteArray result(4, '\0');
    qToBigEndian<quint32>(json.size(), result.data());
    return result + json;
}
bool takeFrame(QByteArray &buffer, QJsonObject &object, QString &error) {
    if (buffer.size() < 4)
        return false;
    const auto length = qFromBigEndian<quint32>(buffer.constData());
    if (!length || length > MaxHeader) {
        error = "Invalid message length";
        return false;
    }
    if (buffer.size() < 4 + length)
        return false;
    QJsonParseError parse;
    const auto doc = QJsonDocument::fromJson(buffer.mid(4, length), &parse);
    buffer.remove(0, 4 + length);
    if (parse.error != QJsonParseError::NoError || !doc.isObject()) {
        error = "Invalid JSON message";
        return false;
    }
    object = doc.object();
    return true;
}
bool writeAll(QTcpSocket &s, const QByteArray &bytes, int timeout, const std::atomic_bool *cancelled) {
    qint64 written = 0;
    while (written < bytes.size()) {
        if (cancelled && *cancelled)
            return false;
        const auto n = s.write(bytes.constData() + written, bytes.size() - written);
        if (n < 0)
            return false;
        written += n;
        QElapsedTimer idle;
        idle.start();
        while (s.bytesToWrite() > 0) {
            if (cancelled && *cancelled)
                return false;
            if (s.waitForBytesWritten(std::min(timeout, 200)))
                idle.restart();
            else if (s.state() != QAbstractSocket::ConnectedState || idle.elapsed() >= timeout)
                return false;
        }
    }
    return true;
}
QByteArray readExact(QTcpSocket &s, qint64 size, int timeout, const std::atomic_bool *cancelled) {
    QByteArray result;
    QElapsedTimer idle;
    idle.start();
    while (result.size() < size) {
        if (cancelled && *cancelled)
            throw std::runtime_error("Transfer cancelled");
        if (!s.bytesAvailable() && !s.waitForReadyRead(std::min(timeout, 200))) {
            if (s.state() != QAbstractSocket::ConnectedState || idle.elapsed() >= timeout)
                throw std::runtime_error("Connection closed or timed out");
            continue;
        }
        result += s.read(size - result.size());
        idle.restart();
    }
    return result;
}
QJsonObject readFrame(QTcpSocket &s, int timeout, const std::atomic_bool *cancelled) {
    const auto header = readExact(s, 4, timeout, cancelled);
    const auto length = qFromBigEndian<quint32>(header.constData());
    if (!length || length > MaxHeader)
        throw std::runtime_error("Invalid message length");
    auto bytes = header + readExact(s, length, timeout, cancelled);
    QJsonObject o;
    QString error;
    if (!takeFrame(bytes, o, error))
        throw std::runtime_error(error.toStdString());
    return o;
}
QString sizeText(qint64 bytes) {
    double value = bytes;
    const QStringList units{"B", "KB", "MB", "GB", "TB"};
    int i = 0;
    while (value >= 1024 && i < units.size() - 1) {
        value /= 1024;
        ++i;
    }
    return QString::number(value, 'f', i ? 1 : 0) + " " + units[i];
}
} // namespace Drop
