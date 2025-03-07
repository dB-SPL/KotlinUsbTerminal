package com.db_spl.kotlin_usb_terminal

import android.app.AlertDialog
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.ListFragment
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.*

class DevicesFragment : ListFragment() {

    private class ListItem(
        val device: UsbDevice,
        val port: Int,
        val driver: UsbSerialDriver?
    )

    private val listItems = ArrayList<ListItem>()
    private lateinit var listAdapter: ArrayAdapter<ListItem>
    private var baudRate = 9600
    private var stopBits = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        listAdapter = object : ArrayAdapter<ListItem>(requireActivity(), 0, listItems) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView
                    ?: requireActivity().layoutInflater.inflate(R.layout.device_list_item, parent, false)
                val item = listItems[position]

                val text1 = view.findViewById<TextView>(R.id.text1)
                val text2 = view.findViewById<TextView>(R.id.text2)

                when {
                    item.driver == null -> {
                        text1.text = "<no driver>"
                    }
                    item.driver.ports.size == 1 -> {
                        text1.text =
                            item.driver.javaClass.simpleName.replace("SerialDriver", "")
                    }
                    else -> {
                        text1.text =
                            item.driver.javaClass.simpleName.replace("SerialDriver", "") +
                                    ", Port " + item.port
                    }
                }

                text2.text = String.format(
                    Locale.US,
                    "Vendor %04X, Product %04X",
                    item.device.vendorId,
                    item.device.productId
                )
                return view
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setListAdapter(null)

        val header = requireActivity().layoutInflater.inflate(R.layout.device_list_header, null, false)
        listView.addHeaderView(header, null, false)

        setEmptyText("<no USB devices found>")
        (listView.emptyView as TextView).textSize = 18f

        setListAdapter(listAdapter)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_devices, menu)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.refresh -> {
                refresh()
                true
            }
            R.id.baud_rate -> {
                val baudRates = resources.getStringArray(R.array.baud_rates)
                val pos = baudRates.indexOf(baudRate.toString())
                val builder = AlertDialog.Builder(requireActivity())
                builder.setTitle("Baud rate")
                builder.setSingleChoiceItems(baudRates, pos) { dialog, which ->
                    baudRate = baudRates[which].toInt()
                    dialog.dismiss()
                }
                builder.create().show()
                true
            }
            R.id.stopBitsMenuItem -> {
                val stopBitsNames = resources.getStringArray(R.array.stopbits_names)
                val pos = stopBitsNames.indexOf(stopBits.toString())
                val builder = AlertDialog.Builder(requireActivity())
                builder.setTitle("Stop bits")
                builder.setSingleChoiceItems(stopBits, pos) { dialog, which ->
                    val stopBitsString = stopBitsNames[which]
                    if (stopBitsString == "1.5") {
                        stopBits = 3
                    } else {
                        stopBits = stopBitsString.toInt()
                    }
                    dialog.dismiss()
                }
                builder.create().show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun refresh() {
        val usbManager = requireActivity().getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDefaultProber = UsbSerialProber.getDefaultProber()
        val usbCustomProber = CustomProber.getCustomProber()

        listItems.clear()

        for (device in usbManager.deviceList.values) {
            var driver = usbDefaultProber.probeDevice(device)
            if (driver == null) {
                driver = usbCustomProber.probeDevice(device)
            }

            if (driver != null) {
                for (port in driver.ports.indices) {
                    listItems.add(ListItem(device, port, driver))
                }
            } else {
                listItems.add(ListItem(device, 0, null))
            }
        }
        listAdapter.notifyDataSetChanged()
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        // Because we added a header, the list items are offset by 1
        val item = listItems[position - 1]
        if (item.driver == null) {
            Toast.makeText(requireActivity(), "no driver", Toast.LENGTH_SHORT).show()
        } else {
            val args = Bundle().apply {
                putInt("device", item.device.deviceId)
                putInt("port", item.port)
                putInt("baud", baudRate)
            }
            val fragment = TerminalFragment()
            fragment.arguments = args
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment, fragment, "terminal")
                .addToBackStack(null)
                .commit()
        }
    }
}
