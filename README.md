# (WIP) Niimbot printer client code to implement in android
[Buy me a coffee](https://www.paypal.com/donate/?business=9YVMYEDHHWT5J&no_recurring=1&item_name=Buy+me+a+coffee%21&currency_code=USD)

Have only tested this code with a Niimbot D11

Code based of python implementation found here: https://github.com/kjy00302/niimprint

# Usage
```
// Initialize and connect to the printer
val printer = NiimbotPrinterClient(bluetoothAddress: String, bluetoothAdapter: BluetoothAdapter)

// Print bitmap image
printer.printLabel(image: Bitmap, width: Int, height: Int, labelQty: Int = 1, labelType: Int = 1, labelDensity: Int = 2)
```


# Documentation for NiimbotPrinterClient

Overview:
This utility class provides functionalities to interact with a Niimbot D11 label printer over a network socket. The main operations include sending, receiving, and processing packets between the application and the printer, and several print utility functions. 

The main functionality of this utility class can be handled with the printLabel method without dealing with any other methods of this class. 

To use the utility, you need to have an instance of the class and an established socket connection. With the socket in place, you can call the printLabel method to handle sending the image data to the printer. 

Methods:

    recv(): Receives Niimbot packets from the printer.
        Returns: A list of Niimbot packets.
        This method continuously reads data from the printer's input stream and constructs Niimbot packets based on specific start and end bytes.

    send(packet: NiimbotPacket): Sends a packet to the printer.
        Parameters: packet – The Niimbot packet to be sent.
        This method writes the packet to the printer's output stream.

    transceive(reqCode: RequestCodeEnum, data: ByteArray, respOffset: Int = 1): Sends a packet to the printer and waits for a response.
        Parameters:
            reqCode – The request code enum.
            data – Byte data to be sent.
            respOffset – An offset for the response code, defaults to 1.
        Returns: A Niimbot packet or null if no response is received after several attempts.

    getInfo(key: InfoEnum): Retrieves printer information based on a given key.
        Parameters: key – The information enum key.
        Returns: Printer information related to the provided key.

    getRfid(): Fetches RFID related data from the printer.
        Returns: A map with RFID data like uuid, barcode, serial, used_len, total_len, and type.

    heartbeat(): Fetches the printer's heartbeat data.
        Returns: A map with printer's status like closingstate, powerlevel, paperstate, and rfidreadstate.

    Printing-related methods: Methods to configure and control the printing process. These include:
        setLabelType(n: Int): Set label type.
        setLabelDensity(n: Int): Set label density.
        startPrint(), endPrint(), startPagePrint(), endPagePrint(): Control printing lifecycle.
        allowPrintClear(): Allows clearing the print.
        setDimension(w: Int, h: Int): Set dimensions for printing.
        setQuantity(n: Int): Set quantity of labels to print.
        getPrintStatus(): Retrieve print status.
        printLabel(image: Bitmap, width: Int, height: Int, labelQty: Int = 1, labelType: Int = 1, labelDensity: Int = 2): Print a given image as a label.

    Utility methods: Methods to support various operations.
        packetToInt(packet: NiimbotPacket): Converts a packet to an integer.
        resizeBitmap(source: Bitmap, desiredWidth: Int, desiredHeight: Int): Resizes a bitmap to desired dimensions.
        rotateBitmap(bitmap: Bitmap, degrees: Float): Rotates a bitmap by a given degree.
