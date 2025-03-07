package com.db_spl.kotlin_usb_terminal

import android.app.Activity
import android.content.*
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.security.InvalidParameterException

class SerialSocket(
    private val context: Context,
    private var connection: UsbDeviceConnection?,
    private var serialPort: UsbSerialPort?
) : SerialInputOutputManager.Listener {

    companion object {
        private const val WRITE_WAIT_MILLIS = 200
        private val TAG = SerialSocket::class.java.simpleName
    }

    private var listener: SerialListener? = null
    private var ioManager: SerialInputOutputManager? = null

    private val disconnectBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            listener?.onSerialIoError(IOException("background disconnect"))
            disconnect()
        }
    }

    init {
        if (context is Activity) {
            throw InvalidParameterException("expected non-UI context")
        }
    }

    val name: String
        get() = serialPort?.driver?.javaClass?.simpleName?.replace("SerialDriver", "") ?: ""

    @Throws(IOException::class)
    fun connect(listener: SerialListener) {
        this.listener = listener

        // Register a broadcast receiver for the "disconnect" action
        ContextCompat.registerReceiver(
            context,
            disconnectBroadcastReceiver,
            IntentFilter(Constants.INTENT_ACTION_DISCONNECT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Some devices (e.g. Arduino) may need DTR/RTS set initially
        try {
            serialPort?.setDTR(true)
            serialPort?.setRTS(true)
        } catch (e: UnsupportedOperationException) {
            Log.d(TAG, "Failed to set initial DTR/RTS", e)
        }

        ioManager = SerialInputOutputManager(serialPort, this).apply {
            start()
        }
    }

    fun disconnect() {
        listener = null
        ioManager?.apply {
            setListener(null)
            stop()
        }
        ioManager = null

        serialPort?.apply {
            try {
                setDTR(false)
                setRTS(false)
            } catch (_: Exception) {
            }
            try {
                close()
            } catch (_: Exception) {
            }
        }
        serialPort = null

        connection?.close()
        connection = null

        try {
            context.unregisterReceiver(disconnectBroadcastReceiver)
        } catch (_: Exception) {
        }
    }

    @Throws(IOException::class)
    fun write(data: ByteArray) {
        if (serialPort == null) throw IOException("not connected")
        serialPort?.write(data, WRITE_WAIT_MILLIS)
    }

    // SerialInputOutputManager.Listener callbacks:
    override fun onNewData(data: ByteArray) {
        listener?.onSerialRead(data)
    }

    override fun onRunError(e: Exception) {
        listener?.onSerialIoError(e)
    }
}
