#include "network.h"
#include "window.h"
#include <QApplication>
#include <QCommandLineParser>
#include <QDir>
#include <QHostInfo>
#include <QJsonDocument>
#include <QMessageBox>
#include <QSettings>
#include <QStandardPaths>
#include <QUuid>
#include <cstdio>
#include <memory>

static void logEvent(const QJsonObject &event) {
    const auto line = QJsonDocument(event).toJson(QJsonDocument::Compact);
    std::fwrite(line.constData(), 1, line.size(), stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
}
int main(int argc, char **argv) {
    bool headless = false;
    for (int i = 1; i < argc; ++i)
        if (QString::fromLocal8Bit(argv[i]) == "--headless")
            headless = true;
    std::unique_ptr<QCoreApplication> app;
    if (headless)
        app = std::make_unique<QCoreApplication>(argc, argv);
    else
        app = std::make_unique<QApplication>(argc, argv);
    QCoreApplication::setApplicationName("Open Drop");
    QCoreApplication::setOrganizationName("OpenDrop");
    QCoreApplication::setApplicationVersion("0.3.0");
    QCommandLineParser parser;
    parser.setApplicationDescription("Simple local-network file transfers");
    parser.addHelpOption();
    parser.addVersionOption();
    parser.addOptions({{"headless", "Run without a window (automation/testing)."},
                       {"auto-accept", "Accept incoming files automatically (headless only)."},
                       {"reject", "Reject incoming files automatically (headless only)."},
                       {"no-discovery", "Disable UDP discovery."},
                       {"name", "Device display name.", "name"},
                       {"id", "Override device identity (testing).", "id"},
                       {"receive-dir", "Folder for received files.", "directory"},
                       {"port", "TCP port (0 chooses a free port).", "port", "45455"},
                       {"send", "Send positional file paths to IPv4:port (headless only).", "address"},
                       {"quit-after-transfer", "Exit after one transfer (headless only)."}});
    parser.addPositionalArgument("files", "Files to send with --send", "[files…]");
    parser.process(*app);
    bool valid;
    const auto port = parser.value("port").toUInt(&valid);
    if (!valid || port > 65535)
        parser.showHelp(2);
    QSettings settings;
    auto id = parser.value("id");
    if (id.isEmpty()) {
        id = settings.value("device/id").toString();
        if (id.isEmpty()) {
            id = QUuid::createUuid().toString(QUuid::WithoutBraces);
            settings.setValue("device/id", id);
        }
    }
    auto name = parser.value("name");
    if (name.isEmpty())
        name = QHostInfo::localHostName().left(80);
    auto directory = parser.value("receive-dir");
    if (directory.isEmpty())
        directory = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation) + "/Open Drop";
    directory = QDir(directory).absolutePath();
    TransferEngine engine(name, directory);
    if (!engine.start(port)) {
        const auto message = "Cannot listen for transfers: " + engine.errorString();
        logEvent({{"event", "error"}, {"message", message}});
        if (!headless)
            QMessageBox::critical(nullptr, "Open Drop", message);
        return 1;
    }
    Discovery discovery(id, name, engine.port());
    std::unique_ptr<Window> window;
    if (!headless) {
        window = std::make_unique<Window>(&engine, &discovery, name, directory);
        window->setWindowIcon(QIcon::fromTheme("opendrop"));
        window->show();
    } else {
        QObject::connect(&engine, &TransferEngine::offered, &engine,
                         [&](Incoming *transfer, QString sender, QStringList files, qint64 bytes) {
                             logEvent({{"event", "offer"},
                                       {"sender", sender},
                                       {"files", QJsonArray::fromStringList(files)},
                                       {"bytes", bytes}});
                             transfer->decide(parser.isSet("auto-accept") && !parser.isSet("reject"));
                         });
        QObject::connect(&engine, &TransferEngine::finished, app.get(), [&](QString message, bool ok) {
            logEvent({{"event", "finished"}, {"ok", ok}, {"message", message}});
            if (parser.isSet("quit-after-transfer"))
                app->exit(ok ? 0 : 1);
        });
        QObject::connect(&discovery, &Discovery::changed, app.get(), [&] {
            QJsonArray peers;
            for (const auto &peer : discovery.peers())
                peers.append(QJsonObject{
                    {"id", peer.id}, {"name", peer.name}, {"address", peer.address}, {"port", peer.port}});
            logEvent({{"event", "peers"}, {"devices", peers}});
        });
    }
    QObject::connect(&discovery, &Discovery::error, app.get(),
                     [](QString message) { logEvent({{"event", "error"}, {"message", message}}); });
    if (!parser.isSet("no-discovery"))
        discovery.start();
    logEvent({{"event", "ready"}, {"name", name}, {"port", engine.port()}, {"directory", directory}});
    if (headless && parser.isSet("send")) {
        const auto target = parser.value("send").split(':');
        bool validPort = false;
        const auto targetPort = target.size() == 2 ? target[1].toUInt(&validPort) : 0;
        const QHostAddress address(target[0]);
        if (!validPort || !targetPort || targetPort > 65535 ||
            address.protocol() != QAbstractSocket::IPv4Protocol)
            return 2;
        QTimer::singleShot(0, &engine, [&, target, targetPort] {
            engine.send({"manual", target[0], "desktop", target[0], static_cast<quint16>(targetPort), 0},
                        parser.positionalArguments());
        });
    }
    return app->exec();
}
