#pragma once
#include <QJsonArray>
#include <QJsonObject>
#include <QString>
#include <QTcpSocket>
#include <atomic>

namespace Drop {
constexpr quint16 DiscoveryPort = 45454;
constexpr quint16 TransferPort = 45455;
constexpr auto MulticastGroup = "239.255.77.77";
constexpr qint64 MaxBytes = 1024LL * 1024 * 1024 * 1024;
constexpr int MaxFiles = 500;
constexpr quint32 MaxHeader = 256 * 1024;
constexpr int Chunk = 64 * 1024;
struct FileInfo {
    QString name;
    qint64 size;
};
bool validName(const QString &name);
QList<FileInfo> parseOffer(const QJsonObject &object, QString &error);
QByteArray frame(const QJsonObject &object);
bool takeFrame(QByteArray &buffer, QJsonObject &object, QString &error);
bool writeAll(QTcpSocket &socket, const QByteArray &bytes, int timeout = 30000,
              const std::atomic_bool *cancelled = nullptr);
QByteArray readExact(QTcpSocket &socket, qint64 size, int timeout = 30000,
                     const std::atomic_bool *cancelled = nullptr);
QJsonObject readFrame(QTcpSocket &socket, int timeout = 30000, const std::atomic_bool *cancelled = nullptr);
QString sizeText(qint64 bytes);
} // namespace Drop
