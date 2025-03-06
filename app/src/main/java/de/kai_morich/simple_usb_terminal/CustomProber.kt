package de.kai_morich.simple_usb_terminal

import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Add devices here that are not known to DefaultProber.
 *
 * If the app should auto-start for these devices, also
 * add IDs to app/src/main/res/xml/usb_device_filter.xml
 */
object CustomProber {
    fun getCustomProber(): UsbSerialProber {
        val customTable = ProbeTable()
        // e.g. device with custom VID+PID:
        customTable.addProduct(0x1234, 0xabcd, FtdiSerialDriver::class.java)
        return UsbSerialProber(customTable)
    }
}
