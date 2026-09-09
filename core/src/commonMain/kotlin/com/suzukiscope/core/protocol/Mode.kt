package com.suzukiscope.core.protocol

/**
 * KWP2000 / ISO 14230 diagnostic service IDs used by Suzuki modules ("sdlmod" protocol),
 * ported from com.malykh.szviewer.common.sdlmod.body.Mode (decompiled sz-viewer.jar).
 */
object Mode {
    const val START_SESSION: Byte = 0x10
    const val READ_FREEZE: Byte = 0x12
    const val CLEAR_DTC: Byte = 0x14
    const val READ_STATUS_DTC: Byte = 0x17
    const val READ_DTC: Byte = 0x18
    const val READ_DTC_INFORMATION: Byte = 0x19
    const val READ_IDENTIFIER: Byte = 0x1A
    const val READ_DATA_BY_LOCAL_IDENTIFIER: Byte = 0x21
    const val READ_DATA_BY_COMMON_IDENTIFIER: Byte = 0x22
    const val CONTROL_IO: Byte = 0x30
    const val TESTER_PRESENT: Byte = 0x3E
    const val START: Byte = 0x81.toByte()          // StartCommunication
    const val STOP: Byte = 0x82.toByte()           // StopCommunication
    const val CONTROL: Byte = 0xA5.toByte()        // Suzuki-proprietary (actuator/control tests)
    const val ERROR_ANSWER: Byte = 0x7F

    // Embedded plain OBD-II service IDs (mode 01/03/04/06/07/09/0A), used in the
    // "experimental" OBD-II-over-K-Line/CAN path.
    const val OBDII_REQUEST_CURRENT_DATA: Byte = 0x01
    const val OBDII_REQUEST_DTC: Byte = 0x03
    const val OBDII_RESET_DTC: Byte = 0x04
    const val OBDII_REQUEST_MONITOR: Byte = 0x06
    const val OBDII_REQUEST_PENDING_DTC: Byte = 0x07
    const val OBDII_REQUEST_VEHICLE_INFORMATION: Byte = 0x09
    const val OBDII_REQUEST_PERMANENT_DTC: Byte = 0x0A

    /** Positive-response service ID: request mode with bit 0x40 set. */
    fun answer(mode: Byte): Byte = ((mode.toInt() and 0xFF) or 0x40).toByte()

    fun isAnswer(requestMode: Byte, answerMode: Byte): Boolean =
        (requestMode.toInt() and 0x40) == 0 &&
            ((requestMode.toInt() and 0xFF) or 0x40) == (answerMode.toInt() and 0xFF)
}
