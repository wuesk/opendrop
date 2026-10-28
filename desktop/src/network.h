#pragma once
#include "protocol.h"
#include <QCryptographicHash>
#include <QDateTime>
#include <QFutureSynchronizer>
#include <QHash>
#include <QObject>
#include <QSaveFile>
#include <QTcpServer>
#include <QTimer>
#include <QUdpSocket>
#include <functional>
#include <memory>

namespace Drop {
// Shared by the outgoing worker and socket-pair interoperability tests.
void sendBatch(QTcpSocket &socket, const QString &name, const QStringList &paths, const QJsonArray &files,
               qint64 total, const std::atomic_bool &stopping,
               const std::function<void(QString, qint64, qint64)> &progress);
} // namespace Drop

struct Peer {
    QString id, name, kind, address;
    quint16 port;
    qint64 seen;
};

class Discovery : public QObject {
    Q_OBJECT
  public:
    Discovery(QString id, QString name, quint16 port, QObject *parent = nullptr);
    bool start();
    QList<Peer> peers() const { return peers_.values(); }
    void announce();
  signals:
    void changed();
    void error(QString message);

  private:
    QString id_, name_;
    quint16 port_;
    QUdpSocket socket_;
    QTimer timer_;
    QHash<QString, Peer> peers_;
};

class Incoming : public QObject {
    Q_OBJECT
  public:
    Incoming(QTcpSocket *socket, QString directory, QObject *parent);
    void decide(bool accept);
  signals:
    void offered(Incoming *transfer, QString sender, QStringList files, qint64 bytes);
    void progress(QString label, qint64 done, qint64 total);
    void finished(QString message, bool success);

  private:
    enum State { Offer, Decision, Body, Digest, Done } state_ = Offer;
    QTcpSocket *socket_;
    QString directory_;
    QByteArray buffer_;
    QList<Drop::FileInfo> files_;
    int index_ = 0;
    qint64 remaining_ = 0, total_ = 0, received_ = 0;
    std::unique_ptr<QSaveFile> file_;
    QCryptographicHash hash_{QCryptographicHash::Sha256};
    QTimer timer_;
    void consume();
    bool nextFile();
    void fail(const QString &message);
    void finish(const QString &message, bool success);
};

class TransferEngine : public QObject {
    Q_OBJECT
  public:
    TransferEngine(QString name, QString directory, QObject *parent = nullptr);
    ~TransferEngine() override;
    bool start(quint16 port = Drop::TransferPort);
    quint16 port() const { return server_.serverPort(); }
    QString errorString() const { return server_.errorString(); }
    void send(const Peer &peer, const QStringList &paths);
  signals:
    void offered(Incoming *transfer, QString sender, QStringList files, qint64 bytes);
    void progress(QString label, qint64 done, qint64 total);
    void finished(QString message, bool success);
    void outgoingChanged(bool busy);

  private:
    QString name_, directory_;
    QTcpServer server_;
    bool sending_ = false;
    int incoming_ = 0;
    std::atomic_bool stopping_ = false;
    QFutureSynchronizer<void> jobs_;
};
