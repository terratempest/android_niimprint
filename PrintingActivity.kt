import PrinterClient
import android.Manifest
import android.annotation.SuppressLint
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
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrintingActivity : AppCompatActivity() {

    companion object {
        const val BLUETOOTH_ENABLE_REQUEST_CODE = 12345
        const val BLUETOOTH_PERMISSION_REQUEST_CODE = 54323
        const val BLUETOOTH_SCAN_PERMISSION_REQUEST_CODE = 44678
    }

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var spinnerBluetoothDevices: Spinner
    private lateinit var spinnerDeviceList: MutableList<String>

    private var labelWidth: Int = 0
    private var labelHeight: Int = 0

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val devices = ArrayList<BluetoothDevice>()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printing)

        // Check if bluetooth permissions are enabled
        bluetoothManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    BLUETOOTH_PERMISSION_REQUEST_CODE
                )
                return
            }
            startActivityForResult(enableBtIntent, BLUETOOTH_ENABLE_REQUEST_CODE)
        }

        if (!hasBluetoothPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                BLUETOOTH_PERMISSION_REQUEST_CODE
            )
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
        adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, spinnerDeviceList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerBluetoothDevices.adapter = adapter

        spinnerBluetoothDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDevice = devices[position]
                // Now you can connect to selectedDevice
            }
        }

        discoverDevices()

        // Niimbot D11 Variables
        val printerResolution = 8 //dots per mm
        labelWidth = 40 // mm
        labelHeight = 12 // mm
        val imageWidth = labelWidth*printerResolution
        val imageHeight = labelHeight*printerResolution

        val imagePrintPreview: ImageView = findViewById(R.id.imagePrintPreview)
        val previewImage: Bitmap = createImage(imageWidth, imageHeight, "Hello, World!", intent.getStringExtra("Hello, World!").toString())
        imagePrintPreview.setImageBitmap(previewImage)


        // Set on click listener for the print button
        val printLabelButton: Button = findViewById(R.id.buttonPrintLabel)
        printLabelButton.setOnClickListener {
            coroutineScope.launch {
                printLabel(previewImage)
            }
        }
    }

    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
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

    private fun discoverDevices() {
        // Register for broadcasts when a device is discovered
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(receiver, filter)

        // Start discovery
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN), BLUETOOTH_SCAN_PERMISSION_REQUEST_CODE)
        }
        bluetoothAdapter.startDiscovery()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BLUETOOTH_ENABLE_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
    override fun onDestroy() {
        super.onDestroy()
        // Unregister the BroadcastReceiver
        unregisterReceiver(receiver)
        coroutineScope.cancel()
    }
    private suspend fun printLabel(image: Bitmap) = withContext(Dispatchers.IO) {
        val selectedDevice = devices[spinnerBluetoothDevices.selectedItemPosition]

        val printer = PrinterClient(selectedDevice.address, bluetoothAdapter)

        // Rotate our sample image for the printer
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
}