package cz.adamkruzela.core2ota.ble

import java.util.UUID

/** GATT UUIDs from Movesense MDS BleManager + Core2 BLE image dump. */
object GattConstants {
    val MOVESENSE_SERVICE: UUID = UUID.fromString("0000FDF3-0000-1000-8000-00805F9B34FB")
    val MOVESENSE_WRITE: UUID = UUID.fromString("6B200001-FF4E-4979-8186-FB7BA486FCD7")
    val MOVESENSE_NOTIFY: UUID = UUID.fromString("6B200002-FF4E-4979-8186-FB7BA486FCD7")

    val NG_SERVICE: UUID = UUID.fromString("61353090-8231-49cc-b57a-886370740041")
    val NG_WRITE: UUID = UUID.fromString("17816557-5652-417f-909f-3aee61e5fa85")
    val NG_NOTIFY: UUID = UUID.fromString("34802252-7185-4d5d-b431-630e7050e8f0")

    val SUUNTO_CUSTOM_SERVICE: UUID =
        UUID.fromString("57a70012-9350-11ed-a1eb-0242ac120002")
    val SUUNTO_CUSTOM_A: UUID = UUID.fromString("57a70011-9350-11ed-a1eb-0242ac120002")
    val SUUNTO_CUSTOM_B: UUID = UUID.fromString("57a70010-9350-11ed-a1eb-0242ac120002")

    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5b3-f393-e0a9-e50e24dcca9e")
    val NUS_RX: UUID = UUID.fromString("6e400002-b5b3-f393-e0a9-e50e24dcca9e")
    val NUS_TX: UUID = UUID.fromString("6e400003-b5b3-f393-e0a9-e50e24dcca9e")

    val SMP_SERVICE: UUID = UUID.fromString("8d53dc1d-1db7-4cd3-868b-8a527460aa84")
    val SMP_CHAR: UUID = UUID.fromString("da2e7828-fbce-4e01-ae9e-261174997c48")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
