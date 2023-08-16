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
