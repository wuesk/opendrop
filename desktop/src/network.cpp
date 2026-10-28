#include "network.h"
#include <QDir>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QFileInfo>
#include <QJsonDocument>
#include <QNetworkDatagram>
#include <QNetworkInterface>
#include <QStorageInfo>
#include <QtConcurrent>
#include <algorithm>
#include <stdexcept>

Discovery::Discovery(QString id, QString name, quint16 port, QObject *parent)
    : QObject(parent), id_(id), name_(name), port_(port) {
    connect(&socket_, &QUdpSocket::readyRead, this, [this] {
        while (socket_.hasPendingDatagrams()) {
            const auto packet = socket_.receiveDatagram(8192);
            const auto o = QJsonDocument::fromJson(packet.data()).object();
            const auto id = o["id"].toString();
            const auto name = o["name"].toString();
            const auto kind = o["kind"].toString();
            const auto port = o["port"].toInt();
            if (o["protocol"] != "opendrop" || o["version"].toInt() != 1 || o["type"] != "hello" ||
                id.isEmpty() || id == id_ || id.size() > 64 || name.isEmpty() || name.size() > 80 ||
                (kind != "desktop" && kind != "android") || port < 1 || port > 65535 ||
                packet.senderAddress().protocol() != QAbstractSocket::IPv4Protocol)
                continue;
            if (!peers_.contains(id) && peers_.size() >= 128)
                continue;
            const bool changedPeer =
                !peers_.contains(id) || peers_[id].name != name || peers_[id].kind != kind ||
                peers_[id].address != packet.senderAddress().toString() || peers_[id].port != port;
            peers_[id] = {id,
                          name,
                          kind,
                          packet.senderAddress().toString(),
                          static_cast<quint16>(port),
                          QDateTime::currentMSecsSinceEpoch()};
            if (changedPeer)
                emit changed();
        }
    });
    connect(&timer_, &QTimer::timeout, this, [this] {
        const auto now = QDateTime::currentMSecsSinceEpoch();
        bool removed = false;
        for (auto it = peers_.begin(); it != peers_.end();) {
            if (now - it->seen > 12000) {
                it = peers_.erase(it);
                removed = true;
            } else
                ++it;
        }
        if (removed)
            emit changed();
        announce();
    });
}
bool Discovery::start() {
    if (!socket_.bind(QHostAddress::AnyIPv4, Drop::DiscoveryPort,
                      QUdpSocket::ShareAddress | QUdpSocket::ReuseAddressHint)) {
        emit error("Device discovery: " + socket_.errorString());
        return false;
    }
    socket_.setSocketOption(QAbstractSocket::MulticastTtlOption, 1);
    for (const auto &interface : QNetworkInterface::allInterfaces())
        if (interface.flags().testFlag(QNetworkInterface::IsUp) &&
            interface.flags().testFlag(QNetworkInterface::CanMulticast))
            socket_.joinMulticastGroup(QHostAddress(Drop::MulticastGroup), interface);
    timer_.start(3000);
    announce();
    return true;
}
void Discovery::announce() {
    const auto bytes = QJsonDocument(QJsonObject{{"protocol", "opendrop"},
                                                 {"version", 1},
                                                 {"type", "hello"},
                                                 {"id", id_},
                                                 {"name", name_},
                                                 {"kind", "desktop"},
                                                 {"port", port_}})
                           .toJson(QJsonDocument::Compact);
    socket_.writeDatagram(bytes, QHostAddress::Broadcast, Drop::DiscoveryPort);
    for (const auto &interface : QNetworkInterface::allInterfaces()) {
        if (!interface.flags().testFlag(QNetworkInterface::IsUp) ||
            interface.flags().testFlag(QNetworkInterface::IsLoopBack))
            continue;
        for (const auto &entry : interface.addressEntries())
            if (entry.ip().protocol() == QAbstractSocket::IPv4Protocol && !entry.broadcast().isNull())
                socket_.writeDatagram(bytes, entry.broadcast(), Drop::DiscoveryPort);
        if (interface.flags().testFlag(QNetworkInterface::CanMulticast)) {
            socket_.setMulticastInterface(interface);
            socket_.writeDatagram(bytes, QHostAddress(Drop::MulticastGroup), Drop::DiscoveryPort);
        }
    }
}

Incoming::Incoming(QTcpSocket *socket, QString directory, QObject *parent)
    : QObject(parent), socket_(socket), directory_(directory) {
    socket_->setParent(this);
    socket_->setReadBufferSize(2 * Drop::Chunk);
    timer_.setSingleShot(true);
    connect(&timer_, &QTimer::timeout, this, [this] { fail("Transfer timed out"); });
    connect(socket_, &QTcpSocket::readyRead, this, &Incoming::consume);
    connect(socket_, &QTcpSocket::disconnected, this, [this] {
        if (state_ != Done)
            finish("Sender disconnected; incomplete file discarded", false);
    });
    connect(socket_, &QTcpSocket::errorOccurred, this, [this] {
        if (state_ != Done)
            finish(socket_->errorString(), false);
    });
    timer_.start(30000);
    // Defer processing until the engine has connected the offer signals.
    QTimer::singleShot(0, this, &Incoming::consume);
}
void Incoming::consume() {
    if (state_ == Done)
        return;
    if (state_ == Decision) {
        if (socket_->bytesAvailable())
            fail("Unexpected data before acceptance");
        return;
    }
    if (socket_->bytesAvailable())
        timer_.start(30000);
    buffer_ += socket_->readAll();
    if (state_ == Offer) {
        QJsonObject o;
        QString error;
        if (!Drop::takeFrame(buffer_, o, error)) {
            if (!error.isEmpty())
                fail(error);
            return;
        }
        files_ = Drop::parseOffer(o, error);
        if (!error.isEmpty()) {
            fail(error);
            return;
        }
        if (!buffer_.isEmpty()) {
            fail("Unexpected data before acceptance");
            return;
        }
        QStringList names;
        for (const auto &f : files_) {
            names << f.name;
            total_ += f.size;
        }
        state_ = Decision;
        timer_.start(60000);
        emit offered(this, o["sender"].toString(), names, total_);
        return;
    }
    while (state_ == Body || state_ == Digest) {
        if (state_ == Body) {
            const auto count = std::min<qint64>(buffer_.size(), remaining_);
            if (count) {
                if (file_->write(buffer_.constData(), count) != count) {
                    fail("Could not save file: " + file_->errorString());
                    return;
                }
                hash_.addData(QByteArrayView(buffer_.constData(), count));
                buffer_.remove(0, count);
                remaining_ -= count;
                received_ += count;
                emit progress("Receiving " + files_[index_].name, received_, total_);
            }
            if (remaining_)
                return;
            state_ = Digest;
        }
        if (buffer_.size() < 32)
            return;
        if (hash_.result() != buffer_.left(32)) {
            fail("Checksum mismatch; incomplete file discarded");
            return;
        }
        buffer_.remove(0, 32);
        if (!file_->commit()) {
            fail("Could not finish saving file");
            return;
        }
        file_.reset();
        socket_->write(Drop::frame({{"ok", true}}));
        ++index_;
        if (index_ == files_.size()) {
            finish(QString("Received %1 file(s) in %2").arg(files_.size()).arg(directory_), true);
            return;
        }
        if (!nextFile())
            return;
    }
}
void Incoming::decide(bool accept) {
    if (state_ != Decision)
        return;
    if (!accept) {
        fail("Transfer declined");
        return;
    }
    if (!QDir().mkpath(directory_)) {
        fail("Cannot create download folder");
        return;
    }
    const QStorageInfo storage(directory_);
    if (storage.isValid() && storage.bytesAvailable() < total_) {
        fail("Not enough free space");
        return;
    }
    if (!nextFile())
        return;
    socket_->write(Drop::frame({{"ok", true}}));
    timer_.start(30000);
    emit progress("Receiving files…", 0, total_);
    consume();
}
bool Incoming::nextFile() {
    const auto info = files_[index_];
    QString path = QDir(directory_).filePath(info.name);
    const QFileInfo original(info.name);
    int suffix = 1;
    while (QFileInfo::exists(path) || QFileInfo(path).isSymLink()) {
        const auto ext = original.suffix();
        path = QDir(directory_)
                   .filePath(original.completeBaseName() + QString(" (%1)").arg(suffix++) +
                             (ext.isEmpty() ? "" : "." + ext));
    }
    file_ = std::make_unique<QSaveFile>(path);
    file_->setDirectWriteFallback(false);
    if (!file_->open(QIODevice::WriteOnly)) {
        fail("Cannot open destination: " + file_->errorString());
        return false;
    }
    hash_.reset();
    remaining_ = info.size;
    state_ = Body;
    return true;
}
void Incoming::fail(const QString &message) {
    if (state_ == Done)
        return;
    socket_->write(Drop::frame({{"ok", false}, {"error", message}}));
    finish(message, false);
}
void Incoming::finish(const QString &message, bool success) {
    if (state_ == Done)
        return;
    state_ = Done;
    timer_.stop();
    file_.reset();
    emit finished(message, success);
    // Keep the socket alive until the last acknowledgement has left its write buffer.
    connect(socket_, &QTcpSocket::disconnected, this, &QObject::deleteLater);
    socket_->flush();
    socket_->disconnectFromHost();
    if (socket_->state() == QAbstractSocket::UnconnectedState)
        deleteLater();
    else
        QTimer::singleShot(5000, this, [this] {
            socket_->abort();
            deleteLater();
        });
}

TransferEngine::TransferEngine(QString name, QString directory, QObject *parent)
    : QObject(parent), name_(name), directory_(directory) {
    connect(&server_, &QTcpServer::newConnection, this, [this] {
        while (server_.hasPendingConnections()) {
            auto *socket = server_.nextPendingConnection();
            if (incoming_ >= 1) {
                connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
                socket->write(Drop::frame({{"ok", false}, {"error", "Device is busy"}}));
                socket->flush();
                socket->disconnectFromHost();
                if (socket->state() == QAbstractSocket::UnconnectedState)
                    socket->deleteLater();
                else
                    QTimer::singleShot(5000, socket, [socket] {
                        socket->abort();
                        socket->deleteLater();
                    });
                continue;
            }
            ++incoming_;
            auto *transfer = new Incoming(socket, directory_, this);
            connect(transfer, &Incoming::offered, this, &TransferEngine::offered);
            connect(transfer, &Incoming::progress, this, &TransferEngine::progress);
            connect(transfer, &Incoming::finished, this, [this](QString message, bool ok) {
                --incoming_;
                emit finished(message, ok);
            });
        }
    });
}
TransferEngine::~TransferEngine() {
    stopping_ = true;
    server_.close();
    jobs_.waitForFinished();
}
bool TransferEngine::start(quint16 port) {
    if (server_.listen(QHostAddress::AnyIPv4, port))
        return true;
    return port != 0 && server_.listen(QHostAddress::AnyIPv4, 0);
}
void TransferEngine::send(const Peer &peer, const QStringList &paths) {
    if (sending_) {
        emit finished("Another outgoing transfer is running", false);
        return;
    }
    if (paths.isEmpty() || paths.size() > Drop::MaxFiles) {
        emit finished("Choose between 1 and 500 files", false);
        return;
    }
    QJsonArray files;
    qint64 total = 0;
    for (const auto &path : paths) {
        const QFileInfo info(path);
        if (!info.isFile() || !info.isReadable() || !Drop::validName(info.fileName()) ||
            info.size() > Drop::MaxBytes - total) {
            emit finished("Cannot send this file: " + path, false);
            return;
        }
        total += info.size();
        files.append(QJsonObject{{"name", info.fileName()}, {"size", info.size()}});
    }
    sending_ = true;
    emit outgoingChanged(true);
    emit progress("Waiting for " + peer.name + " to accept…", 0, total);
    const auto name = name_;
    jobs_.addFuture(QtConcurrent::run([this, peer, paths, files, total, name] {
        QString result;
        bool success = false;
        try {
            QTcpSocket socket;
            socket.setReadBufferSize(Drop::MaxHeader + 4);
            QEventLoop connectLoop;
            QTimer poll;
            poll.setInterval(100);
            QElapsedTimer connecting;
            connecting.start();
            QObject::connect(&poll, &QTimer::timeout, &connectLoop, [&] {
                if (stopping_ || connecting.elapsed() >= 10000)
                    connectLoop.quit();
            });
            QObject::connect(&socket, &QTcpSocket::connected, &connectLoop, &QEventLoop::quit);
            QObject::connect(&socket, &QTcpSocket::errorOccurred, &connectLoop, &QEventLoop::quit);
            socket.connectToHost(peer.address, peer.port);
            poll.start();
            if (socket.state() == QAbstractSocket::ConnectingState)
                connectLoop.exec();
            poll.stop();
            if (socket.state() != QAbstractSocket::ConnectedState || stopping_)
                throw std::runtime_error("Could not connect to device");
            Drop::sendBatch(
                socket, name, paths, files, total, stopping_,
                [this](QString label, qint64 done, qint64 size) { emit progress(label, done, size); });
            result = QString("Sent %1 file(s) to %2").arg(paths.size()).arg(peer.name);
            success = true;
        } catch (const std::exception &e) {
            result = QString::fromUtf8(e.what());
        }
        QMetaObject::invokeMethod(
            this,
            [this, result, success] {
                sending_ = false;
                emit outgoingChanged(false);
                emit finished(result, success);
            },
            Qt::QueuedConnection);
    }));
}

void Drop::sendBatch(QTcpSocket &socket, const QString &name, const QStringList &paths,
                     const QJsonArray &files, qint64 total, const std::atomic_bool &stopping,
                     const std::function<void(QString, qint64, qint64)> &progress) {
    if (!writeAll(socket,
                  frame({{"protocol", "opendrop"},
                         {"version", 1},
                         {"type", "offer"},
                         {"sender", name},
                         {"files", files}}),
                  30000, &stopping))
        throw std::runtime_error("Could not send offer");
    auto reply = readFrame(socket, 65000, &stopping);
    if (reply["ok"] != true)
        throw std::runtime_error(reply["error"].toString("Transfer declined").toStdString());
    qint64 sent = 0;
    for (int i = 0; i < paths.size(); ++i) {
        QFile file(paths[i]);
        if (!file.open(QIODevice::ReadOnly))
            throw std::runtime_error("Could not read source file");
        qint64 left = files[i].toObject()["size"].toInteger();
        QCryptographicHash hash(QCryptographicHash::Sha256);
        QElapsedTimer update;
        update.start();
        while (left > 0) {
            const auto data = file.read(std::min<qint64>(Chunk, left));
            if (data.isEmpty())
                throw std::runtime_error("Source file changed or could not be read");
            hash.addData(data);
            if (!writeAll(socket, data, 30000, &stopping))
                throw std::runtime_error("Connection interrupted");
            left -= data.size();
            sent += data.size();
            if (update.elapsed() > 80 || left == 0) {
                progress("Sending " + QFileInfo(file).fileName(), sent, total);
                update.restart();
            }
        }
        if (!writeAll(socket, hash.result(), 30000, &stopping))
            throw std::runtime_error("Connection interrupted");
        reply = readFrame(socket, 30000, &stopping);
        if (reply["ok"] != true)
            throw std::runtime_error(reply["error"].toString("Receiver could not save file").toStdString());
    }
}
