// Apache RTR 160 4V (TVS project "N112") protocol driver.
//
// This vehicle does not speak SmartXonnect. Its MSPL "Tarang Mini" Bluetooth
// module exposes a serial-style service (`5500`, see GattProfile.APACHE_N112)
// carrying short ASCII-tagged frames. Everything here was established on the
// rider's own bike on 2026-11-14, three ways:
//
//  1. Read from the official app's `N112SendDataToCluster` /
//     `N112ClusterDataReceiver` (TVS Connect 8.14.3, disassembled locally, kept
//     out of the repo under research/vendor/).
//  2. An HCI capture of the official app talking to the bike: exact bytes and
//     order of every frame in both directions.
//  3. A manual replay in nRF Connect with no TVS app involved: the bike began
//     streaming 0.15 s after `57 11`.
//
// No handshake, no checksum, no encryption. The bike sends nothing until the
// phone has switched notifications on (every connection — it forgets), sent a
// phone-status frame, and then `57 11`.

package dev.tvsopen.protocol

/** Phone-side values for the cluster's status icons, already in cluster scales. */
data class PhoneStatus(
    /** Mobile signal, 0..4 (Android's `SignalStrength.level`). */
    val signalLevel: Int = 0,
    /** Phone battery, 0..5, see [ApacheN112Driver.batteryLevel]. */
    val batteryLevel: Int = 0,
    val missedCalls: Int = 0,
    val unreadMessages: Int = 0,
    /** 0 unknown/other, 1 = 2G, 2 = 3G, 3 = LTE, see [ApacheN112Driver.networkClass]. */
    val networkType: Int = 0,
)

/** What a driver may put in its opening frames. */
data class OpeningContext(
    val hourOfDay: Int,
    val minute: Int,
    val riderName: String?,
    val deviceNickname: String?,
    val phoneStatus: PhoneStatus,
)

class ApacheN112Driver(
    override val model: VehicleModel = VehicleModel.APACHE_160,
) : ProtocolDriver {

    override val capabilities = Capabilities(
        // No find-my frame exists for this bike. Navigation is ported from the
        // official N112 sender (see buildNavFrames), untested on the cluster.
        findMyScooter = false,
        navigationHud = true,
        // Ported from the official sender, not yet seen on the cluster.
        textMessages = true,
        callerId = true,
        timeSync = true,
        keepalive = true,
        fuelLowNibble = false,
    )

    override fun confidence() = mapOf(
        "link + opening sequence" to Confidence.VERIFIED_FROM_APK,
        "telemetry records" to Confidence.VERIFIED_FROM_APK,
        "caller ID + SMS" to Confidence.NEEDS_CAPTURE,
        "navigation frames" to Confidence.NEEDS_CAPTURE,
        "fuel scale (8 bars, one reading)" to Confidence.NEEDS_CAPTURE,
    )

    override val hasOpeningSequence: Boolean = true

    // ---- inbound ----

    /** Records are ASCII tags, so any known tag letter can lead a notification. */
    override fun acceptsStartId(startId: Int): Boolean = startId.toChar() in RECORD_LENGTHS

    override fun decode(raw: ByteArray): DecodedFrame? {
        val records = parseRecords(raw)
        if (records.isEmpty()) return null
        val values = LinkedHashMap<Int, Long>()
        val fields = LinkedHashMap<Int, Field>()
        fun put(field: Field, value: Long) {
            fields[field.ordinal] = field
            values[field.ordinal] = value
        }
        for (r in records) {
            val p = r.payload
            when (r.tag) {
                // Speed: one byte, plus a unit flag (0 km/h, 1 mph). 0xFF means
                // "no reading" and the official parser shows it as 0.
                'A' -> {
                    val v = u8(p, 0)
                    val kmh = if (v == 0xFF) 0 else if (u8(p, 1) == 1) mphToKmh(v) else v
                    put(Field.SPEED, kmh.toLong())
                }
                // Odometer: three bytes big-endian in tenths, plus a unit flag.
                // Left in tenths: Session divides ODOMETER by 10 once.
                'O' -> {
                    val tenths = (u8(p, 0).toLong() shl 16) or
                        (u8(p, 1).toLong() shl 8) or u8(p, 2).toLong()
                    val km = if (u8(p, 3) == 1) Math.round(tenths * MILE_KM) else tenths
                    put(Field.ODOMETER, km)
                }
                'R' -> put(Field.ENGINE_RPM, u16(p, 0).toLong())
                'C' -> put(Field.DISTANCE_TO_EMPTY, u16(p, 0).toLong())
                'I' -> put(Field.TOP_SPEED, u8(p, 0).toLong())
                'Z' -> put(Field.AVERAGE_SPEED, u8(p, 0).toLong())
                'P' -> put(Field.GEAR_POSITION, u8(p, 0).toLong())
                // Fuel, raw. Session turns it into bars via [fuelBars].
                'F' -> put(Field.FUEL_LEVEL, u8(p, 0).toLong())
                // Warning-lamp state (0x00..0x03, 0xFF). At idle it alternates
                // 00/02 every half second, so its meaning needs confirming
                // before it drives a warning on screen.
                'E' -> put(Field.VEHICLE_DIAGNOSTICS, u8(p, 0).toLong())
                // Handlebar call button. The official parseDisconnectCall acts
                // on 1 by rejecting the call, then sending its auto-reply SMS.
                'Y' -> put(Field.CALL_DISCONNECT, u8(p, 0).toLong())
                else -> Unit // V heartbeat, U unique id, etc.: link only.
            }
        }
        return DecodedFrame(
            startId = raw[0].toInt() and 0xFF,
            dataId = RECORDS_DATA_ID,
            fields = fields,
            values = values,
            raw = raw,
        )
    }

    // ---- outbound ----

    /** `4C 5B`, every 2 s for as long as the link lasts, exactly as captured. */
    override fun buildPing(state: DashState): ByteArray = KEEPALIVE.copyOf()

    override fun buildPhoneStatus(status: PhoneStatus): ByteArray = phoneStatus(status)

    override fun openingSequence(ctx: OpeningContext): List<ByteArray> {
        val time = time(ctx.hourOfDay, ctx.minute)
        val status = phoneStatus(ctx.phoneStatus)
        val rider = ctx.riderName?.takeIf { it.isNotBlank() }?.let { riderName(it) }
        val device = deviceName(ctx.deviceNickname?.takeIf { it.isNotBlank() } ?: DEFAULT_NICKNAME)
        // Order from the HCI capture of the official app (2026-11-14):
        // V, X x3, 57 11, H x2, M, H, M, 4C 5B. The rider name is skipped when
        // none is saved rather than sending an empty greeting.
        return buildList {
            add(device)
            repeat(3) { add(status) }
            add(START.copyOf())
            add(time)
            add(time)
            rider?.let { add(it) }
            add(time)
            rider?.let { add(it) }
            add(KEEPALIVE.copyOf())
        }
    }

    override fun buildRiderName(name: String): ByteArray = riderName(name)

    /** Incoming call: `C` + name (or number) + `$`. Ported, untested on the cluster. */
    override fun buildCallerFrames(number: String, countryCode: String?, name: String?): List<ByteArray> {
        val who = name?.takeIf { it.isNotBlank() } ?: number
        if (who.isBlank()) return emptyList()
        return listOf(callerName(who))
    }

    /** Only the "call over" state has an N112 frame: `D 01` clears the caller screen. */
    /**
     * The official app resends the caller every 400 ms while the phone rings
     * (BaseConnectHelperService.onCallIncoming's timer); sent once, the
     * cluster drops its call screen after a few seconds.
     */
    override val callerRepeatMs: Long get() = 400L

    override fun buildCallerInfo(number: String, name: String?, state: Int): ByteArray? =
        if (state == Command.CALL_IDLE) CLEAR_CALLER.copyOf()
        else buildCallerFrames(number, null, name).firstOrNull()

    /** New message: `I` + sender + `$`. Ported, untested on the cluster. */
    override fun buildSms(text: String): ByteArray? =
        text.takeIf { it.isNotBlank() }?.let { smsSender(it) }

    /**
     * Turn-by-turn, from the official `TVSMMapActivity.N112sendNavigationData`.
     * Sent every [navFrameIntervalMs] while navigating. Ported, not yet seen on
     * the cluster: verify with an nRF Connect replay first.
     */
    override fun buildNavFrames(nav: NavStep): List<ByteArray> =
        navFrames(nav.pictogram, nav.distanceToTurnMeters)

    /** The official timer period for N112 navigation (`schedule(task, 0, 400)`). */
    override val navFrameIntervalMs: Long = 400L

    override fun buildNavControl(nav: NavStep): ByteArray? = null
    override fun buildNavText(nav: NavStep): ByteArray? = null
    override fun buildTextRow(row: Int, text: String): ByteArray? = null
    override fun buildChallengeResponse(challenge: ByteArray): ByteArray? = null

    companion object {
        /** Synthetic data id for a decoded N112 notification; not a wire byte. */
        const val RECORDS_DATA_ID = 0x112

        const val DEFAULT_NICKNAME = "APACHE"

        /** Longest name the official sender puts in `V`/`M` frames. */
        const val MAX_NAME_CHARS = 16

        /** Longest caller name in a `C` frame (official: substring(0, 18)). */
        const val MAX_CALLER_CHARS = 18

        val START = byteArrayOf(0x57, 0x11)
        val KEEPALIVE = byteArrayOf(0x4C, 0x5B)
        val CLEAR_CALLER = byteArrayOf(0x44, 0x01)

        /**
         * Inbound record lengths, tag byte included, from the official
         * `N112ClusterDataReceiver.parseData`. `V` (a 2-byte heartbeat sent
         * about every 75 ms) is not in that parser but is in every capture.
         */
        val RECORD_LENGTHS: Map<Char, Int> = mapOf(
            'A' to 3, 'T' to 2, 'O' to 5, 'F' to 2, 'D' to 3, 'E' to 2,
            'R' to 3, 'Z' to 3, 'I' to 3, 'Q' to 3, 'P' to 2, 'B' to 2,
            'Y' to 2, 'L' to 2, 'W' to 2, 'J' to 19, 'K' to 19, 'S' to 2,
            'U' to 9, 'C' to 3, 'V' to 2,
        )

        private const val MILE_KM = 1.609344

        data class Record(val tag: Char, val payload: ByteArray) {
            override fun equals(other: Any?) =
                other is Record && tag == other.tag && payload.contentEquals(other.payload)
            override fun hashCode() = tag.hashCode() * 31 + payload.contentHashCode()
        }

        /**
         * Split one notification into its records.
         *
         * Stops at an unknown tag or a record cut short, and keeps what came
         * before. Guessing a length past an unknown tag would misread every
         * following byte as a tag.
         */
        fun parseRecords(raw: ByteArray): List<Record> {
            val out = ArrayList<Record>()
            var i = 0
            while (i < raw.size) {
                val tag = (raw[i].toInt() and 0xFF).toChar()
                val len = RECORD_LENGTHS[tag] ?: break
                if (i + len > raw.size) break
                out += Record(tag, raw.copyOfRange(i + 1, i + len))
                i += len
            }
            return out
        }

        /**
         * Bytes of [raw] that [parseRecords] could not read: an unknown tag, or
         * a record cut short. Zero for a frame read in full.
         */
        fun unparsedBytes(raw: ByteArray): Int =
            raw.size - parseRecords(raw).sumOf { RECORD_LENGTHS.getValue(it.tag) }

        // ---- frame builders (pure, unit-tested) ----

        /** `H`, hour 1..12, minute, PM flag. 00:xx is 12 AM, 12:xx is 12 PM. */
        fun time(hourOfDay: Int, minute: Int): ByteArray {
            val h = hourOfDay.coerceIn(0, 23)
            val hour12 = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            val pm = if (h >= 12) 1 else 0
            return byteArrayOf(0x48, hour12.toByte(), minute.coerceIn(0, 59).toByte(), pm.toByte())
        }

        /** `X`, signal, battery, missed calls, unread, network type. */
        fun phoneStatus(s: PhoneStatus): ByteArray = byteArrayOf(
            0x58,
            s.signalLevel.coerceIn(0, 4).toByte(),
            s.batteryLevel.coerceIn(0, 5).toByte(),
            s.missedCalls.coerceIn(0, 99).toByte(),
            s.unreadMessages.coerceIn(0, 99).toByte(),
            s.networkType.coerceIn(0, 3).toByte(),
        )

        fun deviceName(name: String): ByteArray = tagged('V', name, MAX_NAME_CHARS)
        fun riderName(name: String): ByteArray = tagged('M', name, MAX_NAME_CHARS)
        fun callerName(name: String): ByteArray = tagged('C', name, MAX_CALLER_CHARS)

        /**
         * The official sender caps the whole `I…$` string at 18 characters,
         * which can cut the `$` off a long name. The sender is capped at 16
         * instead, so the terminator always survives.
         */
        fun smsSender(name: String): ByteArray = tagged('I', name, MAX_NAME_CHARS)

        /** `tag` + cluster-safe ASCII (see [PacketBuilder.sanitize]) + `$`. */
        private fun tagged(tag: Char, text: String, maxChars: Int): ByteArray =
            byteArrayOf(tag.code.toByte()) + PacketBuilder.sanitize(text, maxChars) + byteArrayOf(0x24)

        // ---- navigation (official N112sendNavigationData, untested on the cluster) ----
        //
        // Two frames per step:
        //   'N' dist_hi dist_lo unit code lines <line 1> '$'
        //   'O' <line 2> '$'                     (only when lines == 2)
        // unit 0: distance in tenths of a metre, rounded to 50 m steps
        // unit 1: distance in tenths of a km, used beyond 1 km, where the
        //         official app always shows "Continue / Straight" (code 7)

        /** Beyond this many metres the cluster is told to keep straight, in tenths of a km. */
        const val NAV_FAR_METERS = 1000

        /** Official special code for a suggested U-turn (off-route). */
        const val NAV_CODE_U_TURN_SUGGESTION = 0x29

        /**
         * Cluster turn codes 0..25 and their text, from the official
         * `ManeuverUtils.getShortInstruction` string resources. A `:` splits
         * the text across the cluster's two lines.
         */
        val NAV_TEXT: Map<Int, String> = mapOf(
            0 to "Turn Left", 1 to "SHARP:LEFT TURN", 2 to "SLIGHT LEFT",
            3 to "TURN RIGHT", 4 to "SHARP:RIGHT TURN", 5 to "SLIGHT RIGHT",
            6 to "MAKE A:U TURN", 7 to "CONTINUE: STRAIGHT", 8 to "DESTINATION",
            9 to "EXIT: HIGHWAY", 10 to "ENTER:HIGHWAY", 11 to "TAKE NEXT:LEFT",
            12 to "TAKE NEXT:RIGHT", 13 to "LEFT AT:JUNCTION", 14 to "RIGHT AT:JUNCTION",
            15 to "LEFT:AT FORK", 16 to "RIGHT:AT FORK", 17 to "SHARP LEFT:AT JUNCTION",
            18 to "SHARP RIGHT:AT JUNCTION", 19 to "TAKE LEFT: AT JUNCTION",
            20 to "TAKE RIGHT:AT JUNCTION", 21 to "CONTINUE: STRAIGHT", 22 to "CONTINUE:LEFT",
            23 to "LEFT AFTER:RIGHT CURVE", 24 to "CONTINUE:RIGHT", 25 to "RIGHT AFTER: LEFT CURVE",
        )

        /**
         * This app's pictogram id to an N112 turn code.
         *
         * Ids 0..25 are the same TVS table on both sides. The extra ids this
         * app resolves from maps notifications fold onto the nearest N112 code:
         * the compass-style exits 50..57 by direction, a highway exit to 9,
         * and roundabouts and anything unknown to "continue straight" (7).
         */
        fun navCode(pictogram: Int): Int = when (pictogram) {
            in 0..25 -> pictogram
            34 -> 9
            50 -> 7
            51 -> 5
            52 -> 3
            53 -> 4
            54 -> 6
            55 -> 1
            56 -> 0
            57 -> 2
            else -> 7
        }

        /** Distance bytes `[hi, lo, unit]`, see the frame layout above. */
        fun navDistance(meters: Int): ByteArray {
            val m = meters.coerceAtLeast(0)
            val (value, unit) = if (m > NAV_FAR_METERS) {
                (m / 100) to 1 // tenths of a km
            } else {
                (Math.round(m / 50.0).toInt() * 500) to 0 // tenths of a metre, 50 m steps
            }
            val v = value.coerceIn(0, 0xFFFF)
            return byteArrayOf((v shr 8).toByte(), v.toByte(), unit.toByte())
        }

        /** The `N` (and, for two lines, `O`) frames for one navigation step. */
        fun navFrames(pictogram: Int, meters: Int): List<ByteArray> {
            if (pictogram == NAV_CODE_U_TURN_SUGGESTION) {
                return twoLineFrames(byteArrayOf(0, 0, 0), NAV_CODE_U_TURN_SUGGESTION, "Make A", "U Turn")
            }
            val far = meters > NAV_FAR_METERS
            val code = if (far) 7 else navCode(pictogram)
            val text = if (far) "Continue:Straight" else NAV_TEXT.getValue(code)
            val parts = text.split(':').map { it.trim() }.filter { it.isNotEmpty() }
            return twoLineFrames(navDistance(meters), code, parts[0], parts.getOrNull(1))
        }

        private fun twoLineFrames(distance: ByteArray, code: Int, line1: String, line2: String?): List<ByteArray> {
            val lines = if (line2 == null) 1 else 2
            val n = byteArrayOf(0x4E) + distance + byteArrayOf(code.toByte(), lines.toByte()) +
                line1.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x24)
            if (line2 == null) return listOf(n)
            val o = byteArrayOf(0x4F) + line2.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x24)
            return listOf(n, o)
        }

        /** Bars on the RTR 160 4V's fuel gauge. */
        const val FUEL_BARS = 8

        /**
         * Fuel byte (`F` record) to gauge bars, or null when there is no reading.
         *
         * **One-point calibration, unverified:** a full tank, all 8 bars lit,
         * reads `09` (2026-11-14), and the official app raises low fuel below 2.
         * Hence bars = value − 1. A second reading (7 bars should read `08`)
         * confirms or corrects it. `0xFF` means no reading, as elsewhere in N112.
         */
        fun fuelBars(raw: Int): Int? =
            if (raw == 0xFF) null else (raw - 1).coerceIn(0, FUEL_BARS)

        /** Official `parseFuelSegment`: below 2 raises the low-fuel alert. */
        fun isLowFuel(raw: Int): Boolean? = if (raw == 0xFF) null else raw < 2

        /** Official `BluetoothUtil.getBatteryLevel`: percent to 0..5. */
        fun batteryLevel(percent: Int): Int = when {
            percent < 0 -> 0
            percent <= 9 -> 0
            percent <= 19 -> 1
            percent <= 39 -> 2
            percent <= 59 -> 3
            percent <= 79 -> 4
            else -> 5
        }

        /**
         * Official `BluetoothUtil.getSignalType`: Android network type to the
         * cluster's 0..3 class. 5G (NR, 20) is not in the official table and
         * maps to 0 there too.
         */
        fun networkClass(networkType: Int): Int = when (networkType) {
            1, 2, 4, 7, 11 -> 1 // GPRS, EDGE, CDMA, 1xRTT, iDEN
            3, 5, 6, 8, 9, 10, 12, 14, 15 -> 2 // UMTS, EVDO, HSPA family, eHRPD, HSPA+
            13 -> 3 // LTE
            else -> 0
        }

        private fun u8(p: ByteArray, i: Int): Int = if (i < p.size) p[i].toInt() and 0xFF else 0
        private fun u16(p: ByteArray, i: Int): Int = (u8(p, i) shl 8) or u8(p, i + 1)
        private fun mphToKmh(mph: Int): Int = Math.round(mph * MILE_KM).toInt()
    }
}
