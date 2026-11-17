# Consumer ProGuard rules for transport-ble

# BleResilience.refreshStaleGattCache reads these private Kable fields by reflection.
-keepclassmembernames class com.juul.kable.BluetoothDeviceAndroidPeripheral {
    kotlinx.coroutines.flow.MutableStateFlow connection;
}
-keepclassmembernames class com.juul.kable.Connection {
    android.bluetooth.BluetoothGatt gatt;
}
