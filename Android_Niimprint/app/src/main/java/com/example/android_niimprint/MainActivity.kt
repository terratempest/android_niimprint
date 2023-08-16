package com.example.android_niimprint

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.android_niimprint.printing.NiimbotPacket
import com.example.android_niimprint.printing.PrinterClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var spinnerBluetoothDevices: Spinner
    private lateinit var spinnerDeviceList: MutableList<String>
    private var isReceiverRegistered: Boolean = false

    private var labelDPM = 8
    private var labelWidth: Int = 0
    private var labelHeight: Int = 0

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val devices = ArrayList<BluetoothDevice>()

    private val requestBluetoothPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        var allPermissionsGranted = true

        permissions.entries.forEach {
            if (!it.value) {
                allPermissionsGranted = false
            }
            Log.d("PERMISSIONS RESULTS", "${it.key} = ${it.value}")
        }

        if (allPermissionsGranted) {
            setupBluetooth()
        } else {
            Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_SHORT).show()
        }
    }
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printing)

        // Check Permissions
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
        } else {
            setupBluetooth()
        }
    }

    // Define a BroadcastReceiver to get discovered devices
    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when(intent.action!!) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
                    if (device.uuids != null) {
                        devices.add(device)
                        spinnerDeviceList.add(device.name + "\n" + device.address)
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }
    private fun setupBluetooth() {
        bluetoothManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        }

        val settingsDropdown: ConstraintLayout = findViewById(R.id.settingsExpander)
        val settingsList: LinearLayout = findViewById(R.id.settingsLayout)
        // Setting Defaults
        findViewById<Spinner>(R.id.spinnerLabelType).setSelection(0)
        findViewById<Spinner>(R.id.spinnerLabelDensity).setSelection(1)

        settingsDropdown.setOnClickListener {
            if (settingsList.visibility == View.VISIBLE) {
                settingsList.visibility = View.GONE
            } else {
                settingsList.visibility = View.VISIBLE
            }
        }

        spinnerDeviceList = ArrayList()

        spinnerBluetoothDevices = findViewById(R.id.spinnerBluetoothDevices)
        adapter =
            ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, spinnerDeviceList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerBluetoothDevices.adapter = adapter

        spinnerBluetoothDevices.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    // Do nothing or provide feedback to the user
                }

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val selectedDevice = devices[position]
                    // Now you can connect to selectedDevice or perform other operations.
                }
            }

        discoverDevices()

        // Niimbot D11 Variables
        val printerResolution = labelDPM //dots per mm
        labelWidth = 40 // mm
        labelHeight = 12 // mm
        val imageWidth = labelWidth * printerResolution
        val imageHeight = labelHeight * printerResolution

        val imagePrintPreview: ImageView = findViewById(R.id.imagePrintPreview)
        val previewImage: Bitmap =
            createImage(imageWidth, imageHeight, "Hello World!", "Hello World!")
        imagePrintPreview.setImageBitmap(previewImage)


        // Set on click listener for the print button
        val printLabelButton: Button = findViewById(R.id.buttonPrintLabel)
        printLabelButton.setOnClickListener {
            coroutineScope.launch {
                printLabel(previewImage)
            }
        }
    }
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun createImage(width: Int, height: Int, message: String = "Hello, World!", qrContent: String = "Testing QR Code"): Bitmap {
        // Create a blank bitmap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Set up paint for drawing the rectangle
        val paintRect = Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            strokeWidth = 1f
        }

        // Draw a rectangle
        canvas.drawRect(1f, 1f, (width - 2).toFloat(), (height - 2).toFloat(), paintRect)

        // Set up paint for drawing the text
        val paintText = Paint().apply {
            color = Color.BLACK
            //textAlign = Paint.Align.CENTER
            textSize = 30f // You can adjust this value for your needs
            typeface = Typeface.DEFAULT // Use any other typeface if you need
        }

        // Create the QR code
        val qrSize = height - 8
        val qrBitmap = generateQRCodeBitmap(qrContent, qrSize, qrSize)

        // Position to draw the QR code at the center
        val left = 8f
        val top = (canvas.height - qrSize) / 2f

        // Draw QR code
        qrBitmap?.let {
            canvas.drawBitmap(it, left, top, null)
        }

        // Calculate the position to draw the text
        val xPos = 8 + qrSize + 8
        val yPos = (canvas.height / 2 - (paintText.descent() + paintText.ascent()) / 2).toInt()

        // Draw text
        canvas.drawText(message, xPos.toFloat(), yPos.toFloat(), paintText)

        return bitmap
    }

    private fun generateQRCodeBitmap(content: String, width: Int, height: Int): Bitmap? {
        val qrCodeWriter = QRCodeWriter()
        try {
            val bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            return bmp
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun discoverDevices() {
        // Register for broadcasts when a device is discovered
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(receiver, filter)
        isReceiverRegistered = true
        bluetoothAdapter.startDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the BroadcastReceiver
        if(isReceiverRegistered) {unregisterReceiver(receiver)}
        coroutineScope.cancel()
    }
    private suspend fun printLabel(image: Bitmap) = withContext(Dispatchers.IO) {
        val selectedDevice = devices[spinnerBluetoothDevices.selectedItemPosition]

        val printer = PrinterClient(selectedDevice.address, bluetoothAdapter)

        // Rotate our sample image
        val rotatedPreviewImage: Bitmap = rotateBitmap(image, 90f)

        // Grab settings from the settings page
        val labelType: Int = findViewById<Spinner?>(R.id.spinnerLabelType).selectedItem.toString().toInt()
        val labelDensity: Int = findViewById<Spinner>(R.id.spinnerLabelDensity).selectedItem.toString().toInt()
        val labelQuantity: Int = findViewById<EditText>(R.id.editTextLabelQTY).text.toString().toInt()

        printer.setLabelType(labelType)

        printer.setLabelDensity(labelDensity)
        printer.startPrint()
        printer.allowPrintClear()
        printer.startPagePrint()
        printer.setDimension(image.width, image.height)
        printer.setQuantity(labelQuantity)

        // Convert the image to packets and send to the printer
        val packets = NiimbotPacket.naiveEncoder(rotatedPreviewImage)

        for (pkt in packets) {
            printer.send(pkt)
        }

        printer.endPagePrint()
        while (printer.getPrintStatus()["page"] != labelQuantity) {
            Thread.sleep(100)
        }
        printer.endPrint()


    }
    private fun hasBluetoothPermissions(): Boolean {
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        return true
    }

    private fun requestBluetoothPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        requestBluetoothPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
    }
}