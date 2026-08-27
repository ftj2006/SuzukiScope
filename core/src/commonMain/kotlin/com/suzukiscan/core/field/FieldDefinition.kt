package com.suzukiscan.core.field

import kotlinx.serialization.Serializable

/**
 * User-configurable definition of a single live-data field (a gauge/graph source).
 * Nothing about which fields exist is hardcoded in the app — these are loaded from
 * JSON (see [FieldRegistry]) so users can add/edit/remove fields without a rebuild.
 *
 * NOTE: the request/decode parameters below are placeholders/examples. Suzuki's real
 * per-module DID numbers and scale/offset formulas were NOT recovered from the
 * decompiled sources in this pass (see the sdlmod.data.local and sdlmod.data.value
 * packages for the actual per-module decoders to port next) — verify against a real
 * vehicle/the site's "How to read raw values" guide before trusting them.
 */
@Serializable
data class FieldDefinition(
    val id: String,
    val label: String,
    val unit: String,
    val request: RequestSpec,
    val decode: DecodeSpec,
    val gaugeMin: Double = 0.0,
    val gaugeMax: Double = 100.0,
    /** Shown as a gauge on the Dashboard. */
    val enabled: Boolean = true,
    /** Included in the CSV log/recorder when logging is running \u2014 independent of [enabled],
     * but defaults to the same pre-selection as [enabled] unless set separately. */
    val recordEnabled: Boolean = enabled,
    /** Mild indicator level — gauge turns amber at/above this value, if set. */
    val warningThreshold: Double? = null,
    /** Severe level — gauge turns red at/above this value, and triggers a device alert, if set. */
    val criticalThreshold: Double? = null,
    /** Fixed decimal places to display, e.g. 0 for RPM/Speed, 2 for Boost Pressure. Null = auto. */
    val decimals: Int? = null,
    /** Set by a "test all fields" pass (see FieldRegistry) when polling this field failed/timed
     * out on the vehicle actually tested \u2014 lets the config screen hide it by default without
     * losing it, since it may still work on a different vehicle variant/module. */
    val verifiedNoData: Boolean = false,
)

/** How to build the KWP2000/UDS request for this field. */
@Serializable
data class RequestSpec(
    /** Target module address byte (K-Line physical/functional address, or CAN ID for UDS). */
    val targetAddress: Int,
    val isFunctionalAddress: Boolean = false,
    /** Diagnostic service/mode byte, e.g. 0x21 ReadDataByLocalIdentifier, 0x22 ReadDataByCommonIdentifier. */
    val mode: Int,
    /** Identifier/parameter bytes appended after the mode byte. */
    val params: List<Int> = emptyList(),
    /**
     * Raw bytes to strip from the front of the ELM327 answer before decoding — e.g. the 3-byte
     * KWP2000/ISO 14230 header (format+target+source) that ELM327 includes unless "AT H0" is set.
     * 0 for CAN/UDS responses (ELM327 normally reports just the data bytes for those).
     */
    val responsePrefixBytes: Int = 3,
    /** Trailing bytes to strip — e.g. the 1-byte K-Line checksum. 0 for CAN/UDS. */
    val responseSuffixBytes: Int = 1,
)

/** How to turn the raw response payload into a physical value. */
@Serializable
data class DecodeSpec(
    /** Bytes to skip at the start of the response payload (e.g. echoed mode+DID bytes). */
    val skipBytes: Int = 0,
    val byteLength: Int = 1,
    val signed: Boolean = false,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
)
