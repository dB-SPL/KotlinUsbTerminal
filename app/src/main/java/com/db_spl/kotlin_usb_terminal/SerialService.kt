package com.db_spl.kotlin_usb_terminal

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.ArrayDeque

/**
 * Create a notification and queue serial data while the activity is not in the foreground.
 * Uses the listener chain: SerialSocket -> SerialService -> UI fragment
 */
class SerialService : Service(), SerialListener {

    inner class SerialBinder : Binder() {
        fun getService(): SerialService = this@SerialService
    }

    private enum class QueueType { Connect, ConnectError, Read, IoError }

    private class QueueItem {
        var type: QueueType
        var datas: ArrayDeque<ByteArray>? = null
        var e: Exception? = null

        constructor(type: QueueType) {
            this.type = type
            if (type == QueueType.Read) {
                init()
            }
        }

        constructor(type: QueueType, e: Exception) {
            this.type = type
            this.e = e
        }

        constructor(type: QueueType, datas: ArrayDeque<ByteArray>) {
            this.type = type
            this.datas = datas
        }

        fun init() {
            datas = ArrayDeque()
        }

        fun add(data: ByteArray) {
            datas?.add(data)
        }
    }

    private val mainLooperHandler = Handler(Looper.getMainLooper())
    private val binder = SerialBinder()
    private val queue1 = ArrayDeque<QueueItem>()
    private val queue2 = ArrayDeque<QueueItem>()
    private val lastRead = QueueItem(QueueType.Read)

    private var socket: SerialSocket? = null
    private var listener: SerialListener? = null
    private var connected = false

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        cancelNotification()
        disconnect()
        super.onDestroy()
    }

    /**
     * API
     */
    @Throws(IOException::class)
    fun connect(socket: SerialSocket) {
        socket.connect(this)
        this.socket = socket
        connected = true
    }

    fun disconnect() {
        connected = false // ignore data and errors while disconnecting
        cancelNotification()
        socket?.disconnect()
        socket = null
    }

    @Throws(IOException::class)
    fun write(data: ByteArray) {
        if (!connected) throw IOException("not connected")
        socket?.write(data)
    }

    fun attach(listener: SerialListener?) {
        // Must be on main thread
        check(Looper.getMainLooper().thread == Thread.currentThread()) {
            "Not in main thread"
        }
        initNotification()
        cancelNotification()

        synchronized(this) {
            this.listener = listener
        }

        // deliver messages queued before + after attach
        for (item in queue1) {
            when (item.type) {
                QueueType.Connect -> listener?.onSerialConnect()
                QueueType.ConnectError -> listener?.onSerialConnectError(item.e!!)
                QueueType.Read -> listener?.onSerialRead(item.datas!!)
                QueueType.IoError -> listener?.onSerialIoError(item.e!!)
            }
        }
        for (item in queue2) {
            when (item.type) {
                QueueType.Connect -> listener?.onSerialConnect()
                QueueType.ConnectError -> listener?.onSerialConnectError(item.e!!)
                QueueType.Read -> listener?.onSerialRead(item.datas!!)
                QueueType.IoError -> listener?.onSerialIoError(item.e!!)
            }
        }
        queue1.clear()
        queue2.clear()
    }

    fun detach() {
        if (connected) {
            createNotification()
        }
        // Items posted before detach() go into queue1, items after detach() go into queue2
        listener = null
    }

    private fun initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nc = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL,
                "Background service",
                NotificationManager.IMPORTANCE_LOW
            )
            nc.setShowBadge(false)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(nc)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun areNotificationsEnabled(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nc = nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL)
        return nm.areNotificationsEnabled() && nc != null && nc.importance > NotificationManager.IMPORTANCE_NONE
    }

    private fun createNotification() {
        val disconnectIntent = Intent().apply {
            setPackage(packageName)
            action = Constants.INTENT_ACTION_DISCONNECT
        }
        val restartIntent = Intent().apply {
            setClassName(this@SerialService, Constants.INTENT_CLASS_MAIN_ACTIVITY)
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else 0

        val disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, flags)
        val restartPendingIntent = PendingIntent.getActivity(this, 1, restartIntent, flags)

        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(resources.getColor(R.color.colorPrimary))
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (socket != null) {
                    "Connected to ${socket!!.name}"
                } else {
                    "Background Service"
                }
            )
            .setContentIntent(restartPendingIntent)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_clear_white_24dp,
                    "Disconnect",
                    disconnectPendingIntent
                )
            )
            .build()

        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification)
    }

    private fun cancelNotification() {
        stopForeground(true)
    }

    /**
     * SerialListener
     */
    override fun onSerialConnect() {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    mainLooperHandler.post {
                        if (listener != null) {
                            listener!!.onSerialConnect()
                        } else {
                            queue1.add(QueueItem(QueueType.Connect))
                        }
                    }
                } else {
                    queue2.add(QueueItem(QueueType.Connect))
                }
            }
        }
    }

    override fun onSerialConnectError(e: Exception) {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    mainLooperHandler.post {
                        if (listener != null) {
                            listener!!.onSerialConnectError(e)
                        } else {
                            queue1.add(QueueItem(QueueType.ConnectError, e))
                            disconnect()
                        }
                    }
                } else {
                    queue2.add(QueueItem(QueueType.ConnectError, e))
                    disconnect()
                }
            }
        }
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray>) {
        // Not used here
        throw UnsupportedOperationException()
    }

    override fun onSerialRead(data: ByteArray) {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    var first: Boolean
                    synchronized(lastRead) {
                        first = lastRead.datas!!.isEmpty()
                        lastRead.add(data)
                    }
                    if (first) {
                        mainLooperHandler.post {
                            var mergedData: ArrayDeque<ByteArray>?
                            synchronized(lastRead) {
                                mergedData = lastRead.datas
                                lastRead.init()
                            }
                            if (listener != null) {
                                listener!!.onSerialRead(mergedData!!)
                            } else {
                                queue1.add(QueueItem(QueueType.Read, mergedData!!))
                            }
                        }
                    }
                } else {
                    if (queue2.isEmpty() || queue2.last.type != QueueType.Read) {
                        queue2.add(QueueItem(QueueType.Read))
                    }
                    queue2.last.add(data)
                }
            }
        }
    }

    override fun onSerialIoError(e: Exception) {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    mainLooperHandler.post {
                        if (listener != null) {
                            listener!!.onSerialIoError(e)
                        } else {
                            queue1.add(QueueItem(QueueType.IoError, e))
                            disconnect()
                        }
                    }
                } else {
                    queue2.add(QueueItem(QueueType.IoError, e))
                    disconnect()
                }
            }
        }
    }
}
