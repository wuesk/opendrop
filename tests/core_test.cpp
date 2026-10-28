#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#endif
#include "network.h"
#include "window.h"
#include <QApplication>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QPointer>
#include <QScopeGuard>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QtConcurrent>
#include <QtEndian>
#include <QtTest>
#ifndef _WIN32
#include <sys/socket.h>
#include <unistd.h>
#endif

// Preserve sandbox-friendly Unix socket pairs; use loopback TCP on Windows.
static bool connectedSockets(qintptr descriptors[2]) {
#ifdef _WIN32
    struct Winsock {
        bool ready;
        Winsock() {
            WSADATA data;
            ready = WSAStartup(MAKEWORD(2, 2), &data) == 0;
        }
        ~Winsock() {
            if (ready)
                WSACleanup();
        }
    };
    static Winsock winsock;
    if (!winsock.ready)
        return false;
    SOCKET listener = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listener == INVALID_SOCKET)
        return false;
    const auto closeListener = qScopeGuard([&] { closesocket(listener); });
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int length = sizeof(address);
    if (::bind(listener, reinterpret_cast<sockaddr *>(&address), length) != 0 || ::listen(listener, 1) != 0 ||
        ::getsockname(listener, reinterpret_cast<sockaddr *>(&address), &length) != 0)
        return false;
    SOCKET client = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (client == INVALID_SOCKET)
        return false;
    if (::connect(client, reinterpret_cast<sockaddr *>(&address), length) != 0) {
        closesocket(client);
        return false;
    }
    SOCKET server = ::accept(listener, nullptr, nullptr);
    if (server == INVALID_SOCKET) {
        closesocket(client);
        return false;
    }
    descriptors[0] = static_cast<qintptr>(client);
    descriptors[1] = static_cast<qintptr>(server);
    return true;
#else
    int pair[2];
    if (::socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0)
        return false;
    descriptors[0] = pair[0];
    descriptors[1] = pair[1];
    return true;
#endif
}

class CoreTest : public QObject {
    Q_OBJECT
    QTemporaryDir temp_;
    QTcpSocket *client_ = nullptr;
    QPointer<Incoming> incoming_;

    QJsonObject offer(QJsonArray files) {
        return {{"protocol", "opendrop"},
                {"version", 1},
                {"type", "offer"},
                {"sender", "Test phone"},
                {"files", files}};
    }
    QJsonObject entry(QString name, qint64 size) { return {{"name", name}, {"size", size}}; }
    void pair(bool accept = true) {
        qintptr descriptors[2];
        QVERIFY(connectedSockets(descriptors));
        client_ = new QTcpSocket(this);
        auto *server = new QTcpSocket;
        QVERIFY(client_->setSocketDescriptor(descriptors[0]));
        QVERIFY(server->setSocketDescriptor(descriptors[1]));
        incoming_ = new Incoming(server, temp_.path(), this);
        connect(incoming_, &Incoming::offered, this,
                [accept](Incoming *transfer) { transfer->decide(accept); });
    }
    void write(const QByteArray &bytes) {
        QCOMPARE(client_->write(bytes), bytes.size());
        client_->flush();
        QCoreApplication::processEvents();
    }
    QJsonObject reply() {
        for (int i = 0; i < 100 && client_->bytesAvailable() == 0; ++i)
            QTest::qWait(5);
        if (!client_->bytesAvailable())
            return {};
        return Drop::readFrame(*client_, 1000);
    }
  private slots:
    void init() { QVERIFY(temp_.isValid()); }
    void cleanup() {
        if (client_) {
            delete client_;
            client_ = nullptr;
        }
        if (incoming_)
            delete incoming_;
        for (const auto &entry : QDir(temp_.path()).entryList(QDir::Files | QDir::Hidden))
            QFile::remove(temp_.filePath(entry));
    }
    void filenameRules() {
        QVERIFY(Drop::validName("żółw 🐢.txt"));
        QVERIFY(Drop::validName(".hidden"));
        for (const auto &name :
             QStringList{"", ".", "..", "../escape", "a/b", "a\\b", "bad\nname", QString(241, 'a')})
            QVERIFY(!Drop::validName(name));
    }
    void framingAndFragmentation() {
        const auto expected = offer({entry("test", 0)});
        const auto bytes = Drop::frame(expected);
        QByteArray buffer;
        QJsonObject actual;
        QString error;
        for (int i = 0; i < bytes.size() - 1; ++i) {
            buffer += bytes[i];
            QVERIFY(!Drop::takeFrame(buffer, actual, error));
            QVERIFY(error.isEmpty());
        }
        buffer += bytes.back();
        QVERIFY(Drop::takeFrame(buffer, actual, error));
        QCOMPARE(actual, expected);
        QVERIFY(buffer.isEmpty());
    }
    void invalidFrames() {
        for (quint32 length : {0U, Drop::MaxHeader + 1}) {
            QByteArray bytes(4, '\0');
            qToBigEndian(length, bytes.data());
            QJsonObject o;
            QString error;
            QVERIFY(!Drop::takeFrame(bytes, o, error));
            QVERIFY(!error.isEmpty());
        }
        QByteArray bytes = QByteArray::fromHex("000000025b5d");
        QJsonObject o;
        QString error;
        QVERIFY(!Drop::takeFrame(bytes, o, error));
        QVERIFY(!error.isEmpty());
    }
    void invalidOffers() {
        for (const auto &files : QList<QJsonArray>{{},
                                                   {entry("../escape", 0)},
                                                   {entry("x", -1)},
                                                   {entry("x", Drop::MaxBytes + 1)},
                                                   {entry("x", Drop::MaxBytes), entry("y", 1)},
                                                   {QJsonObject{{"name", "x"}, {"size", 0.5}}},
                                                   {QJsonObject{{"name", "x"}, {"size", "2"}}}}) {
            QString error;
            QVERIFY(Drop::parseOffer(offer(files), error).isEmpty());
            QVERIFY(!error.isEmpty());
        }
        QJsonArray tooMany;
        for (int i = 0; i <= Drop::MaxFiles; ++i)
            tooMany.append(entry("x", 0));
        QString error;
        QVERIFY(Drop::parseOffer(offer(tooMany), error).isEmpty());
    }
    void receiveBatchAndCollisions() {
        pair();
        const QByteArray data("a\0b\xff", 4);
        QSignalSpy finished(incoming_, &Incoming::finished);
        write(Drop::frame(offer({entry("żółw.txt", data.size()), entry("empty", 0), entry("żółw.txt", 4)})));
        QCOMPARE(reply()["ok"], QJsonValue(true));
        write(data + QCryptographicHash::hash(data, QCryptographicHash::Sha256));
        QCOMPARE(reply()["ok"], QJsonValue(true));
        write(QCryptographicHash::hash({}, QCryptographicHash::Sha256));
        QCOMPARE(reply()["ok"], QJsonValue(true));
        write(data + QCryptographicHash::hash(data, QCryptographicHash::Sha256));
        QCOMPARE(reply()["ok"], QJsonValue(true));
        QCOMPARE(finished.count(), 1);
        QVERIFY(finished[0][1].toBool());
        for (const auto &name : QStringList{"żółw.txt", "żółw (1).txt"}) {
            QFile file(temp_.filePath(name));
            QVERIFY(file.open(QIODevice::ReadOnly));
            QCOMPARE(file.readAll(), data);
        }
        QVERIFY(QFileInfo(temp_.filePath("empty")).exists());
        QCOMPARE(QFileInfo(temp_.filePath("empty")).size(), 0);
    }
    void checksumFailureDiscardsFile() {
        pair();
        QSignalSpy finished(incoming_, &Incoming::finished);
        write(Drop::frame(offer({entry("bad.bin", 3)})));
        QCOMPARE(reply()["ok"], QJsonValue(true));
        write("abc" + QByteArray(32, '\0'));
        QCOMPARE(reply()["ok"], QJsonValue(false));
        QCOMPARE(finished.count(), 1);
        QVERIFY(!finished[0][1].toBool());
        QVERIFY(!QFileInfo::exists(temp_.filePath("bad.bin")));
    }
    void rejectionSavesNothing() {
        pair(false);
        write(Drop::frame(offer({entry("declined", 5)})));
        QCOMPARE(reply()["ok"], QJsonValue(false));
        QVERIFY(!QFileInfo::exists(temp_.filePath("declined")));
    }
    void interruptedFileIsDiscarded() {
        pair();
        QSignalSpy finished(incoming_, &Incoming::finished);
        write(Drop::frame(offer({entry("partial", 100)})));
        QCOMPARE(reply()["ok"], QJsonValue(true));
        write("partial");
        client_->abort();
        QTRY_COMPARE(finished.count(), 1);
        QVERIFY(!finished[0][1].toBool());
        QVERIFY(!QFileInfo::exists(temp_.filePath("partial")));
    }
    void traversalRejectedBeforeConsent() {
        pair();
        QSignalSpy offered(incoming_, &Incoming::offered);
        write(Drop::frame(offer({entry("../escape", 1)})));
        QCOMPARE(reply()["ok"], QJsonValue(false));
        QCOMPARE(offered.count(), 0);
    }
    void senderStreamsBatchAndHonorsConsent() {
        qintptr descriptors[2];
        QVERIFY(connectedSockets(descriptors));
        client_ = new QTcpSocket(this);
        QVERIFY(client_->setSocketDescriptor(descriptors[0]));
        QByteArray data(1024 * 1024, '\0');
        for (int i = 0; i < data.size(); ++i)
            data[i] = static_cast<char>(i % 256);
        QFile source(temp_.filePath("żółw.bin"));
        QVERIFY(source.open(QIODevice::WriteOnly));
        QCOMPARE(source.write(data), data.size());
        source.close();
        QFile empty(temp_.filePath("empty"));
        QVERIFY(empty.open(QIODevice::WriteOnly));
        empty.close();
        const QStringList paths{source.fileName(), empty.fileName()};
        const QJsonArray files{entry("żółw.bin", data.size()), entry("empty", 0)};
        auto cancelled = std::make_shared<std::atomic_bool>(false);
        auto job =
            QtConcurrent::run([descriptor = descriptors[1], paths, files, cancelled, size = data.size()] {
                QTcpSocket socket;
                if (!socket.setSocketDescriptor(descriptor))
                    return QString("Cannot attach socket");
                try {
                    Drop::sendBatch(socket, "Test computer", paths, files, size, *cancelled,
                                    [](QString, qint64, qint64) {});
                    return QString();
                } catch (const std::exception &e) {
                    return QString::fromUtf8(e.what());
                }
            });
        const auto guard = qScopeGuard([&] {
            *cancelled = true;
            job.waitForFinished();
        });
        const auto metadata = Drop::readFrame(*client_, 5000);
        QCOMPARE(metadata["files"].toArray(), files);
        QCOMPARE(client_->bytesAvailable(), 0);
        QVERIFY(Drop::writeAll(*client_, Drop::frame({{"ok", true}})));
        QCOMPARE(Drop::readExact(*client_, data.size(), 5000), data);
        QCOMPARE(Drop::readExact(*client_, 32, 5000),
                 QCryptographicHash::hash(data, QCryptographicHash::Sha256));
        QVERIFY(Drop::writeAll(*client_, Drop::frame({{"ok", true}})));
        QCOMPARE(Drop::readExact(*client_, 32, 5000),
                 QCryptographicHash::hash({}, QCryptographicHash::Sha256));
        QVERIFY(Drop::writeAll(*client_, Drop::frame({{"ok", true}})));
        job.waitForFinished();
        QVERIFY2(job.result().isEmpty(), qPrintable(job.result()));
    }
    void waitingSenderCanBeCancelled() {
        qintptr descriptors[2];
        QVERIFY(connectedSockets(descriptors));
        client_ = new QTcpSocket(this);
        QVERIFY(client_->setSocketDescriptor(descriptors[0]));
        auto cancelled = std::make_shared<std::atomic_bool>(false);
        auto job = QtConcurrent::run([descriptor = descriptors[1], cancelled] {
            QTcpSocket socket;
            socket.setSocketDescriptor(descriptor);
            try {
                Drop::sendBatch(socket, "Test computer", {"unused"},
                                {QJsonObject{{"name", "test"}, {"size", 1}}}, 1, *cancelled,
                                [](QString, qint64, qint64) {});
                return QString();
            } catch (const std::exception &e) {
                return QString::fromUtf8(e.what());
            }
        });
        const auto guard = qScopeGuard([&] {
            *cancelled = true;
            job.waitForFinished();
        });
        QCOMPARE(Drop::readFrame(*client_, 5000)["type"], QJsonValue("offer"));
        *cancelled = true;
        QElapsedTimer timer;
        timer.start();
        job.waitForFinished();
        QVERIFY(timer.elapsed() < 1500);
        QCOMPARE(job.result(), QString("Transfer cancelled"));
    }
    void desktopWindow() {
        TransferEngine engine("Test computer", temp_.path());
        Discovery discovery("test", "Test computer", 45455);
        Window window(&engine, &discovery, "Test computer", temp_.path());
        window.show();
        QCoreApplication::processEvents();
        QCOMPARE(window.windowTitle(), QString("Open Drop"));
        QVERIFY(window.findChild<DeviceList *>());
        const auto buttons = window.findChildren<QPushButton *>();
        QVERIFY(buttons.size() >= 3);
        const auto path = qEnvironmentVariable("OPENDROP_SCREENSHOT");
        if (!path.isEmpty())
            QVERIFY(window.grab().save(path));
    }
};
QTEST_MAIN(CoreTest)
#include "core_test.moc"
