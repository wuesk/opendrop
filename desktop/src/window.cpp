#include "window.h"
#include <QApplication>
#include <QDesktopServices>
#include <QDir>
#include <QDragEnterEvent>
#include <QFileDialog>
#include <QFileInfo>
#include <QInputDialog>
#include <QMessageBox>
#include <QMimeData>
#include <QPointer>
#include <QStyle>
#include <QUrl>
#include <QVBoxLayout>
#include <algorithm>

DeviceList::DeviceList(QWidget *parent) : QListWidget(parent) {
    setAcceptDrops(true);
    setDragDropMode(QAbstractItemView::DropOnly);
    setSpacing(8);
    setIconSize({32, 32});
    setSelectionMode(QAbstractItemView::SingleSelection);
    setAccessibleName("Nearby devices. Drop files onto a device to send.");
}
void DeviceList::dragEnterEvent(QDragEnterEvent *event) {
    if (event->mimeData()->hasUrls())
        event->acceptProposedAction();
}
void DeviceList::dragMoveEvent(QDragMoveEvent *event) {
    if (event->mimeData()->hasUrls() && itemAt(event->position().toPoint()))
        event->acceptProposedAction();
    else
        event->ignore();
}
void DeviceList::dropEvent(QDropEvent *event) {
    auto *item = itemAt(event->position().toPoint());
    if (!item)
        return;
    QStringList paths;
    for (const auto &url : event->mimeData()->urls()) {
        if (!url.isLocalFile())
            return;
        paths << url.toLocalFile();
    }
    if (!paths.isEmpty()) {
        event->acceptProposedAction();
        emit filesDropped(item->data(Qt::UserRole).toString(), paths);
    }
}
Window::Window(TransferEngine *engine, Discovery *discovery, QString name, QString directory)
    : engine_(engine), discovery_(discovery), directory_(directory) {
    setWindowTitle("Open Drop");
    resize(650, 620);
    setMinimumSize(460, 420);
    auto *root = new QWidget;
    auto *layout = new QVBoxLayout(root);
    layout->setContentsMargins(30, 28, 30, 24);
    layout->setSpacing(14);
    auto *heading = new QLabel("Open Drop");
    auto font = heading->font();
    font.setPointSize(27);
    font.setBold(true);
    heading->setFont(font);
    layout->addWidget(heading);
    auto *subtitle = new QLabel("Your files. Your Wi-Fi. Just drop.");
    layout->addWidget(subtitle);
    auto *identity = new QLabel("This computer · " + name);
    identity->setTextFormat(Qt::PlainText);
    identity->setTextInteractionFlags(Qt::TextSelectableByMouse);
    layout->addWidget(identity);
    layout->addSpacing(12);
    layout->addWidget(new QLabel("NEARBY DEVICES"));
    empty_ = new QLabel("Looking for your devices…\n\nOpen Open Drop on your phone or another computer\nand "
                        "connect both devices to the same local network.");
    empty_->setAlignment(Qt::AlignCenter);
    empty_->setWordWrap(true);
    layout->addWidget(empty_, 1);
    list_ = new DeviceList;
    layout->addWidget(list_, 1);
    auto *hint = new QLabel("Drop one or more files onto a device, or select it and choose files.");
    hint->setWordWrap(true);
    layout->addWidget(hint);
    auto *actions = new QHBoxLayout;
    send_ = new QPushButton("Choose files…");
    send_->setEnabled(false);
    actions->addWidget(send_);
    auto *connectButton = new QPushButton("Connect by address…");
    actions->addWidget(connectButton);
    auto *downloads = new QPushButton("Downloads");
    actions->addWidget(downloads);
    layout->addLayout(actions);
    progress_ = new QProgressBar;
    progress_->setRange(0, 1000);
    progress_->hide();
    layout->addWidget(progress_);
    status_ = new QLabel("Ready · Transfers stay on your local network");
    status_->setTextFormat(Qt::PlainText);
    status_->setWordWrap(true);
    status_->setTextInteractionFlags(Qt::TextSelectableByMouse);
    layout->addWidget(status_);
    auto *address =
        new QLabel(QString("Receive port: %1 · Files saved to %2").arg(engine_->port()).arg(directory_));
    address->setTextFormat(Qt::PlainText);
    address->setWordWrap(true);
    address->setTextInteractionFlags(Qt::TextSelectableByMouse);
    layout->addWidget(address);
    setCentralWidget(root);
    connect(list_, &QListWidget::itemSelectionChanged, this,
            [this] { send_->setEnabled(!busy_ && list_->currentItem()); });
    connect(list_, &QListWidget::itemDoubleClicked, this,
            [this](QListWidgetItem *item) { chooseFiles(item->data(Qt::UserRole).toString()); });
    connect(list_, &DeviceList::filesDropped, this, &Window::sendTo);
    connect(send_, &QPushButton::clicked, this, [this] {
        if (auto *item = list_->currentItem())
            chooseFiles(item->data(Qt::UserRole).toString());
    });
    connect(downloads, &QPushButton::clicked, this, [this] {
        QDir().mkpath(directory_);
        QDesktopServices::openUrl(QUrl::fromLocalFile(directory_));
    });
    connect(connectButton, &QPushButton::clicked, this, [this] {
        const auto address =
            QInputDialog::getText(this, "Connect by address", "Device IPv4 address (optionally :port):");
        if (address.isEmpty())
            return;
        const auto parts = address.trimmed().split(':');
        QHostAddress host(parts[0]);
        bool validPort = true;
        int port = parts.size() > 1 ? parts[1].toInt(&validPort) : Drop::TransferPort;
        if (parts.size() > 2 || host.protocol() != QAbstractSocket::IPv4Protocol || !validPort || port < 1 ||
            port > 65535) {
            status_->setText("Enter an IPv4 address, for example 192.168.1.20:45455");
            return;
        }
        const auto files = QFileDialog::getOpenFileNames(this, "Choose files to send");
        if (!files.isEmpty())
            engine_->send({"manual", address, "desktop", host.toString(), static_cast<quint16>(port), 0},
                          files);
    });
    connect(discovery_, &Discovery::changed, this, &Window::refresh);
    connect(discovery_, &Discovery::error, status_, &QLabel::setText);
    connect(engine_, &TransferEngine::offered, this,
            [this](Incoming *transfer, QString sender, QStringList names, qint64 bytes) {
                QPointer<Incoming> guarded(transfer);
                auto *box =
                    new QMessageBox(QMessageBox::Question, "Receive files?",
                                    QString("%1 wants to send %2 file(s) (%3).\n\n%4")
                                        .arg(sender)
                                        .arg(names.size())
                                        .arg(Drop::sizeText(bytes))
                                        .arg(names.mid(0, 8).join('\n') + (names.size() > 8 ? "\n…" : "")),
                                    QMessageBox::Yes | QMessageBox::No, this);
                box->setTextFormat(Qt::PlainText);
                box->setDefaultButton(QMessageBox::No);
                box->setAttribute(Qt::WA_DeleteOnClose);
                connect(box, &QMessageBox::finished, this, [guarded](int result) {
                    if (guarded)
                        guarded->decide(result == QMessageBox::Yes);
                });
                connect(transfer, &QObject::destroyed, box, &QMessageBox::reject);
                box->open();
                QApplication::alert(this);
            });
    connect(engine_, &TransferEngine::progress, this, [this](QString label, qint64 done, qint64 total) {
        status_->setText(label + " · " + Drop::sizeText(done) + " / " + Drop::sizeText(total));
        progress_->show();
        progress_->setValue(total ? static_cast<int>(done * 1000 / total) : 0);
    });
    connect(engine_, &TransferEngine::finished, this, [this](QString message, bool ok) {
        status_->setText((ok ? "✓ " : "Transfer stopped: ") + message);
        progress_->hide();
    });
    connect(engine_, &TransferEngine::outgoingChanged, this, [this, connectButton](bool busy) {
        busy_ = busy;
        connectButton->setEnabled(!busy);
        list_->setAcceptDrops(!busy);
        send_->setEnabled(!busy && list_->currentItem());
    });
    refresh();
}
void Window::refresh() {
    const auto selected =
        list_->currentItem() ? list_->currentItem()->data(Qt::UserRole).toString() : QString();
    list_->clear();
    auto peers = discovery_->peers();
    std::sort(peers.begin(), peers.end(),
              [](const Peer &a, const Peer &b) { return a.name.toLower() < b.name.toLower(); });
    for (const auto &peer : peers) {
        const auto icon = QIcon::fromTheme(peer.kind == "android" ? "smartphone" : "computer",
                                           style()->standardIcon(QStyle::SP_ComputerIcon));
        auto *item = new QListWidgetItem(
            icon,
            peer.name + "\n" + (peer.kind == "android" ? "Android phone" : "Computer") + " · " + peer.address,
            list_);
        item->setData(Qt::UserRole, peer.id);
        item->setSizeHint({0, 76});
        item->setToolTip("Drop files to send to " + peer.name);
        if (peer.id == selected)
            list_->setCurrentItem(item);
    }
    empty_->setVisible(peers.isEmpty());
    list_->setVisible(!peers.isEmpty());
}
void Window::chooseFiles(const QString &id) {
    const auto files = QFileDialog::getOpenFileNames(this, "Choose files to send");
    if (!files.isEmpty())
        sendTo(id, files);
}
void Window::sendTo(const QString &id, const QStringList &paths) {
    for (const auto &peer : discovery_->peers())
        if (peer.id == id) {
            engine_->send(peer, paths);
            return;
        }
    status_->setText("Device went offline. Open Open Drop on that device and try again.");
}
