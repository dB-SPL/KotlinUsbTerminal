package com.db-spl.kotlin-usb-terminal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.*
import android.text.*
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.*
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.XonXoffFilter
import java.io.IOException
import java.util.*

class TerminalFragment : Fragment(), ServiceConnection, SerialListener {

    private enum class Connected {
        False, Pending, True
    }

    // The send button can be:
    // - Idle (normal)
    // - Busy (still sending, e.g. large data or slow baud)
    // - Disabled (if flow-control is blocking)
    private enum class SendButtonState {
        Idle, Busy, Disabled
    }

    private var connected = Connected.False
    private var deviceId = 0
    private var portNum = 0
    private var baudRate = 0
    private var usbSerialPort: UsbSerialPort? = null

    private lateinit var service: SerialService

    private lateinit var receiveText: TextView
    private lateinit var sendText: EditText
    private lateinit var sendBtn: ImageButton

    private lateinit var controlLines: ControlLines
    private var flowControlFilter: XonXoffFilter? = null

    private var hexEnabled = false
    private lateinit var hexWatcher: TextUtil.HexWatcher

    private var pendingNewline = false
    private var newline = TextUtil.newline_crlf

    // UI main thread handler
    private val mainLooper = Handler(Looper.getMainLooper())

    // Where we hold references to the background service
    private var serviceBound = false

    // region Fragment Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        deviceId = requireArguments().getInt("device")
        portNum = requireArguments().getInt("port")
        baudRate = requireArguments().getInt("baud")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)

        receiveText = view.findViewById(R.id.receive_text)
        receiveText.setTextColor(resources.getColor(R.color.colorRecieveText)) // keep XML naming: "recieve" vs "receive"
        receiveText.movementMethod = ScrollingMovementMethod()

        sendText = view.findViewById(R.id.send_text)
        sendBtn = view.findViewById(R.id.send_btn)

        sendBtn.setOnClickListener {
            val str = sendText.text.toString()
            if (str.isNotEmpty()) {
                send(str)
                sendText.setText("")
            }
        }

        // toggling HEX input
        hexWatcher = TextUtil.HexWatcher(sendText)
        hexWatcher.enable(hexEnabled)
        sendText.addTextChangedListener(hexWatcher)

        // track control line states & flow control
        controlLines = ControlLines()
        controlLines.onCreateView(view)

        return view
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(requireActivity(), SerialService::class.java)
        requireActivity().bindService(intent, this, Context.BIND_AUTO_CREATE)
        serviceBound = true
    }

    override fun onStop() {
        if (serviceBound) {
            requireActivity().unbindService(this)
            serviceBound = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (connected == Connected.False) {
            // connect automatically on start-up
            connect()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    // endregion

    // region Options Menu

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.hex)?.isChecked = hexEnabled
        controlLines.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.baud_rate -> {
                val baudRates = resources.getStringArray(R.array.baud_rates)
                val pos = baudRates.indexOf(baudRate.toString())
                AlertDialog.Builder(requireActivity()).apply {
                    setTitle("Baud rate")
                    setSingleChoiceItems(baudRates, pos) { dialog, which ->
                        baudRate = baudRates[which].toInt()
                        dialog.dismiss()
                    }
                }.create().show()
                return true
            }
            R.id.hex -> {
                hexEnabled = !hexEnabled
                sendText.setText("")
                hexWatcher.enable(hexEnabled)
                sendText.hint = if (hexEnabled) "HEX mode" else ""
                item.isChecked = hexEnabled
                return true
            }
            R.id.controlLines -> {
                val newVal = !item.isChecked
                item.isChecked = controlLines.showControlLines(newVal)
                return true
            }
            R.id.flowControl -> {
                controlLines.selectFlowControl()
                return true
            }
            R.id.backgroundNotification -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                    } else {
                        showNotificationSettings()
                    }
                }
                return true
            }
            R.id.sendBreak -> {
                try {
                    usbSerialPort?.apply {
                        setBreak(true)
                        Thread.sleep(100)
                        status("send BREAK")
                        setBreak(false)
                    }
                } catch (e: Exception) {
                    status("send BREAK failed: ${e.message}")
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // endregion

    // region USB Connection

    private fun connect() {
        connect(null)
    }

    private fun connect(permissionGranted: Boolean?) {
        var device: UsbDevice? = null
        val usbManager = requireActivity().getSystemService(Context.USB_SERVICE) as UsbManager
        for (v in usbManager.deviceList.values) {
            if (v.deviceId == deviceId) {
                device = v
                break
            }
        }
        if (device == null) {
            status("connection failed: device not found")
            return
        }

        var driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device)
        }
        if (driver == null) {
            status("connection failed: no driver for device")
            return
        }
        if (driver.ports.size < portNum) {
            status("connection failed: not enough ports at device")
            return
        }

        usbSerialPort = driver.ports[portNum]
        val usbConnection = usbManager.openDevice(driver.device)
        if (usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(Constants.INTENT_ACTION_GRANT_USB)
            intent.setPackage(requireActivity().packageName)
            val usbPermissionIntent = PendingIntent.getBroadcast(requireActivity(), 0, intent, flags)
            usbManager.requestPermission(driver.device, usbPermissionIntent)
            return
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.device)) {
                status("connection failed: permission denied")
            } else {
                status("connection failed: open failed")
            }
            return
        }

        connected = Connected.Pending
        try {
            usbSerialPort?.open(usbConnection)
            try {
                usbSerialPort?.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (e: UnsupportedOperationException) {
                status("Setting serial parameters failed: ${e.message}")
            }

            val socket = SerialSocket(requireActivity().applicationContext, usbConnection, usbSerialPort)
            service.connect(socket)
            // USB connect is synchronous: connect-success/error returned immediately
            onSerialConnect()
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        controlLines.stop()
        service.disconnect()
        updateSendBtn(SendButtonState.Idle)
        usbSerialPort = null
    }

    // endregion

    // region Sending Data

    private fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(requireActivity(), "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        val msg: String
        val data: ByteArray
        if (hexEnabled) {
            // Convert typed hex into bytes and add the newline in hex form
            val sb = StringBuilder()
            TextUtil.toHexString(sb, TextUtil.fromHexString(str))
            TextUtil.toHexString(sb, newline.toByteArray())
            msg = sb.toString()
            data = TextUtil.fromHexString(msg)
        } else {
            msg = str
            data = (str + newline).toByteArray()
        }
        try {
            // Show the data in the local UI
            val spn = SpannableStringBuilder("$msg\n")
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.colorSendText)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText.append(spn)

            // Send it out over USB
            service.write(data)

        } catch (e: SerialTimeoutException) {
            // e.g. writing large data at low baud rate or suspended by flow control
            mainLooper.post {
                sendAgain(data, e.bytesTransferred)
            }
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun sendAgain(data0: ByteArray, offset: Int) {
        updateSendBtn(if (controlLines.sendAllowed) SendButtonState.Busy else SendButtonState.Disabled)
        if (connected != Connected.True) {
            return
        }
        val data = if (offset == 0) {
            data0
        } else {
            // skip the portion that was already written
            data0.copyOfRange(offset, data0.size)
        }

        try {
            service.write(data)
        } catch (e: SerialTimeoutException) {
            mainLooper.post {
                sendAgain(data, e.bytesTransferred)
            }
            return
        } catch (e: Exception) {
            onSerialIoError(e)
        }
        updateSendBtn(if (controlLines.sendAllowed) SendButtonState.Idle else SendButtonState.Disabled)
    }

    // endregion

    // region Receiving Data

    private fun receive(datas: ArrayDeque<ByteArray>) {
        val spn = SpannableStringBuilder()

        for (chunk in datas) {
            var data = chunk

            // Check for flow control (XON/XOFF) that might remove certain bytes
            flowControlFilter?.let { filter ->
                data = filter.filter(data)
            }

            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n')
            } else {
                var msg = String(data)
                // If CRLF is used, unify it or handle CR+LF separately
                if (newline == TextUtil.newline_crlf && msg.isNotEmpty()) {
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                    if (pendingNewline && msg[0] == '\n') {
                        // remove the extra CR from the tail of previous data
                        if (spn.length >= 2) {
                            spn.delete(spn.length - 2, spn.length)
                        } else {
                            val edt = receiveText.editableText
                            if (edt != null && edt.length >= 2) {
                                edt.delete(edt.length - 2, edt.length)
                            }
                        }
                    }
                    pendingNewline = msg[msg.length - 1] == '\r'
                }
                spn.append(TextUtil.toCaretString(msg, newline.isNotEmpty()))
            }
        }

        receiveText.append(spn)
    }

    fun status(str: String) {
        val spn = SpannableStringBuilder("$str\n")
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.colorStatusText)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        receiveText.append(spn)
    }

    private fun updateSendBtn(state: SendButtonState) {
        sendBtn.isEnabled = (state == SendButtonState.Idle)
        sendBtn.imageAlpha = if (state == SendButtonState.Idle) 255 else 64
        sendBtn.setImageResource(
            if (state == SendButtonState.Disabled) R.drawable.ic_block_white_24dp
            else R.drawable.ic_send_white_24dp
        )
    }

    // endregion

    // region Notification Permission (Android 13+)

    private fun showNotificationSettings() {
        val intent = Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
            putExtra("android.provider.extra.APP_PACKAGE", requireActivity().packageName)
        }
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, @NonNull permissions: Array<out String>, @NonNull grantResults: IntArray) {
        if (permissions.contentEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS))) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !service.areNotificationsEnabled()) {
                showNotificationSettings()
            }
        }
    }

    // endregion

    // region SerialListener callbacks

    override fun onSerialConnect() {
        status("connected")
        connected = Connected.True
        controlLines.start()
    }

    override fun onSerialConnectError(e: Exception) {
        status("connection failed: " + e.message)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray) {
        val datas = ArrayDeque<ByteArray>()
        datas.add(data)
        receive(datas)
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray>) {
        receive(datas)
    }

    override fun onSerialIoError(e: Exception) {
        status("connection lost: " + e.message)
        disconnect()
    }

    // endregion

    // region ServiceConnection callbacks

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialService.SerialBinder).getService()
        service.attach(this)
        if (connected == Connected.Pending) {
            onSerialConnect() // (fake) synchronous connect completion
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service.detach()
    }

    // endregion

    // region Internal Classes (ControlLines)

    inner class ControlLines {
        private val refreshInterval = 200 // msec
        private val runnable = Runnable { run() }

        private var frame: View? = null
        private var rtsBtn: ToggleButton? = null
        private var ctsBtn: ToggleButton? = null
        private var dtrBtn: ToggleButton? = null
        private var dsrBtn: ToggleButton? = null
        private var cdBtn: ToggleButton? = null
        private var riBtn: ToggleButton? = null

        var showControlLines = false
            private set
        var flowControl = UsbSerialPort.FlowControl.NONE
            private set

        var sendAllowed = true
            private set

        fun onCreateView(view: View) {
            frame = view.findViewById(R.id.controlLines)
            rtsBtn = view.findViewById(R.id.controlLineRts)
            ctsBtn = view.findViewById(R.id.controlLineCts)
            dtrBtn = view.findViewById(R.id.controlLineDtr)
            dsrBtn = view.findViewById(R.id.controlLineDsr)
            cdBtn = view.findViewById(R.id.controlLineCd)
            riBtn = view.findViewById(R.id.controlLineRi)

            rtsBtn?.setOnClickListener { toggle(it) }
            dtrBtn?.setOnClickListener { toggle(it) }
        }

        fun onPrepareOptionsMenu(menu: Menu) {
            try {
                val scl = usbSerialPort?.supportedControlLines ?: emptySet()
                val sfc = usbSerialPort?.supportedFlowControl ?: emptySet()

                menu.findItem(R.id.controlLines)?.isEnabled = scl.isNotEmpty()
                menu.findItem(R.id.controlLines)?.isChecked = showControlLines
                menu.findItem(R.id.flowControl)?.isEnabled = (sfc.size > 1)
            } catch (ignored: Exception) {
            }
        }

        fun selectFlowControl() {
            val sfc = usbSerialPort?.supportedFlowControl ?: emptySet()
            val fc = usbSerialPort?.flowControl ?: UsbSerialPort.FlowControl.NONE
            val names = ArrayList<String>()
            val values = ArrayList<UsbSerialPort.FlowControl>()
            var pos = 0

            names.add("<none>")
            values.add(UsbSerialPort.FlowControl.NONE)

            if (sfc.contains(UsbSerialPort.FlowControl.RTS_CTS)) {
                names.add("RTS/CTS control lines")
                values.add(UsbSerialPort.FlowControl.RTS_CTS)
                if (fc == UsbSerialPort.FlowControl.RTS_CTS) pos = names.size - 1
            }
            if (sfc.contains(UsbSerialPort.FlowControl.DTR_DSR)) {
                names.add("DTR/DSR control lines")
                values.add(UsbSerialPort.FlowControl.DTR_DSR)
                if (fc == UsbSerialPort.FlowControl.DTR_DSR) pos = names.size - 1
            }
            if (sfc.contains(UsbSerialPort.FlowControl.XON_XOFF)) {
                names.add("XON/XOFF characters")
                values.add(UsbSerialPort.FlowControl.XON_XOFF)
                if (fc == UsbSerialPort.FlowControl.XON_XOFF) pos = names.size - 1
            }
            if (sfc.contains(UsbSerialPort.FlowControl.XON_XOFF_INLINE)) {
                // some devices or library versions name it differently
                names.add("XON/XOFF characters")
                values.add(UsbSerialPort.FlowControl.XON_XOFF_INLINE)
                if (fc == UsbSerialPort.FlowControl.XON_XOFF_INLINE) pos = names.size - 1
            }

            AlertDialog.Builder(requireActivity()).apply {
                setTitle("Flow Control")
                setSingleChoiceItems(names.toTypedArray(), pos) { dialog, which ->
                    dialog.dismiss()
                    try {
                        flowControl = values[which]
                        usbSerialPort?.flowControl = flowControl
                        flowControlFilter = if (usbSerialPort?.flowControl == UsbSerialPort.FlowControl.XON_XOFF_INLINE) {
                            XonXoffFilter()
                        } else null
                        start()
                    } catch (e: Exception) {
                        status("Set flow control failed: ${e.javaClass.name} ${e.message}")
                        flowControl = UsbSerialPort.FlowControl.NONE
                        flowControlFilter = null
                        start()
                    }
                }
                setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                setNeutralButton("Info") { dialog, _ ->
                    dialog.dismiss()
                    AlertDialog.Builder(requireActivity()).apply {
                        setTitle("Flow Control")
                        setMessage(
                            "If sending is blocked by the external device, the 'Send' button changes to a 'Blocked' icon."
                        )
                        create().show()
                    }
                }
            }.create().show()
        }

        fun showControlLines(show: Boolean): Boolean {
            showControlLines = show
            start()
            return showControlLines
        }

        fun start() {
            if (showControlLines) {
                try {
                    val lines = usbSerialPort?.supportedControlLines
                    rtsBtn?.visibility = if (lines?.contains(UsbSerialPort.ControlLine.RTS) == true) View.VISIBLE else View.INVISIBLE
                    ctsBtn?.visibility = if (lines?.contains(UsbSerialPort.ControlLine.CTS) == true) View.VISIBLE else View.INVISIBLE
                    dtrBtn?.visibility = if (lines?.contains(UsbSerialPort.ControlLine.DTR) == true) View.VISIBLE else View.INVISIBLE
                    dsrBtn?.visibility = if (lines?.contains(UsbSerialPort.ControlLine.DSR) == true) View.VISIBLE else View.INVISIBLE
                    cdBtn?.visibility  = if (lines?.contains(UsbSerialPort.ControlLine.CD)  == true) View.VISIBLE else View.INVISIBLE
                    riBtn?.visibility  = if (lines?.contains(UsbSerialPort.ControlLine.RI)  == true) View.VISIBLE else View.INVISIBLE
                } catch (e: IOException) {
                    showControlLines = false
                    status("getSupportedControlLines() failed: ${e.message}")
                }
            }
            frame?.visibility = if (showControlLines) View.VISIBLE else View.GONE

            if (flowControl == UsbSerialPort.FlowControl.NONE) {
                sendAllowed = true
                updateSendBtn(SendButtonState.Idle)
            }

            mainLooper.removeCallbacks(runnable)
            if (showControlLines || flowControl != UsbSerialPort.FlowControl.NONE) {
                run()
            }
        }

        fun stop() {
            mainLooper.removeCallbacks(runnable)
            sendAllowed = true
            updateSendBtn(SendButtonState.Idle)
            rtsBtn?.isChecked = false
            ctsBtn?.isChecked = false
            dtrBtn?.isChecked = false
            dsrBtn?.isChecked = false
            cdBtn?.isChecked  = false
            riBtn?.isChecked  = false
        }

        private fun run() {
            if (connected != Connected.True) return
            try {
                if (showControlLines) {
                    val lines = usbSerialPort?.controlLines
                    if (rtsBtn?.isChecked != (lines?.contains(UsbSerialPort.ControlLine.RTS) == true)) {
                        rtsBtn?.isChecked = !(rtsBtn?.isChecked ?: false)
                    }
                    if (ctsBtn?.isChecked != (lines?.contains(UsbSerialPort.ControlLine.CTS) == true)) {
                        ctsBtn?.isChecked = !(ctsBtn?.isChecked ?: false)
                    }
                    if (dtrBtn?.isChecked != (lines?.contains(UsbSerialPort.ControlLine.DTR) == true)) {
                        dtrBtn?.isChecked = !(dtrBtn?.isChecked ?: false)
                    }
                    if (dsrBtn?.isChecked != (lines?.contains(UsbSerialPort.ControlLine.DSR) == true)) {
                        dsrBtn?.isChecked = !(dsrBtn?.isChecked ?: false)
                    }
                    if (cdBtn?.isChecked != (lines?.contains(UsbSerialPort.ControlLine.CD) == true)) {
                        cdBtn?.isChecked = !(cdBtn?.isChecked ?: false)
                    }
                    if (riBtn?.isChecked != (lines?.contains(UsbSerialPort.ControlLine.RI) == true)) {
                        riBtn?.isChecked = !(riBtn?.isChecked ?: false)
                    }
                }

                if (flowControl != UsbSerialPort.FlowControl.NONE) {
                    sendAllowed = when (usbSerialPort?.flowControl) {
                        UsbSerialPort.FlowControl.DTR_DSR -> usbSerialPort?.dsr == true
                        UsbSerialPort.FlowControl.RTS_CTS -> usbSerialPort?.cts == true
                        UsbSerialPort.FlowControl.XON_XOFF -> usbSerialPort?.xon == true
                        UsbSerialPort.FlowControl.XON_XOFF_INLINE ->
                            (flowControlFilter != null && flowControlFilter!!.xon)
                        else -> true
                    }
                    updateSendBtn(if (sendAllowed) SendButtonState.Idle else SendButtonState.Disabled)
                }

                mainLooper.postDelayed(runnable, refreshInterval.toLong())

            } catch (e: IOException) {
                status("getControlLines() failed: ${e.message} -> stopped control line refresh")
            }
        }

        private fun toggle(v: View) {
            val btn = v as ToggleButton
            if (connected != Connected.True) {
                btn.isChecked = !btn.isChecked
                Toast.makeText(requireActivity(), "not connected", Toast.LENGTH_SHORT).show()
                return
            }
            var ctrl = ""
            try {
                if (btn == rtsBtn) {
                    ctrl = "RTS"
                    usbSerialPort?.rts = btn.isChecked
                }
                if (btn == dtrBtn) {
                    ctrl = "DTR"
                    usbSerialPort?.dtr = btn.isChecked
                }
            } catch (e: IOException) {
                status("set$ctrl failed: ${e.message}")
            }
        }
    }

    // endregion
}
