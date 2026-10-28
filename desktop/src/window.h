#pragma once
#include "network.h"
#include <QLabel>
#include <QListWidget>
#include <QMainWindow>
#include <QProgressBar>
#include <QPushButton>

class DeviceList : public QListWidget {
    Q_OBJECT
  public:
    explicit DeviceList(QWidget *parent = nullptr);
  signals:
    void filesDropped(QString peerId, QStringList paths);

  protected:
    void dragEnterEvent(QDragEnterEvent *event) override;
    void dragMoveEvent(QDragMoveEvent *event) override;
    void dropEvent(QDropEvent *event) override;
};

class Window : public QMainWindow {
    Q_OBJECT
  public:
    Window(TransferEngine *engine, Discovery *discovery, QString name, QString directory);

  private:
    TransferEngine *engine_;
    Discovery *discovery_;
    QString directory_;
    DeviceList *list_;
    QLabel *empty_, *status_;
    QProgressBar *progress_;
    QPushButton *send_;
    bool busy_ = false;
    void refresh();
    void chooseFiles(const QString &id);
    void sendTo(const QString &id, const QStringList &paths);
};
