package com.hplay.receiver

import java.util.Locale

/** Textos fijos de UPnP AV 1.0 (MediaRenderer:1) y utilidades SOAP. Mismo contrato que receivers/windows/Upnp.cs. */
object Upnp {
    const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val HPLAY = "urn:hplay-org:device:Receiver:1"

    /** Formatos que reproduce el WebView (Chromium) directamente o con hls.js. */
    val SINK = listOf(
        "video/mp4", "video/x-m4v", "video/webm", "video/x-matroska", "application/vnd.apple.mpegurl", "application/x-mpegurl",
        "audio/mpegurl", "video/quicktime", "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/wav", "audio/flac",
    ).joinToString(",") { "http-get:*:$it:*" }

    fun esc(s: String) = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }.replace("&amp;", "&")

    fun description(uuid: String, name: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><device><deviceType>$RENDERER</deviceType>" +
            "<friendlyName>${esc(name)}</friendlyName><manufacturer>HPlay</manufacturer><manufacturerURL>https://github.com/PolHernaez/hplay</manufacturerURL>" +
            "<modelDescription>HPlay Receiver</modelDescription><modelName>HPlay Receiver</modelName><modelNumber>1.0</modelNumber>" +
            "<UDN>uuid:$uuid</UDN><dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>" +
            service(AVT, "AVTransport", "avt") + service(RC, "RenderingControl", "rc") + service(CM, "ConnectionManager", "cm") +
            "</serviceList></device></root>"

    private fun service(type: String, id: String, path: String) =
        "<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$id</serviceId><SCPDURL>/$path.xml</SCPDURL>" +
            "<controlURL>/upnp/control/$path</controlURL><eventSubURL>/upnp/event/$path</eventSubURL></service>"

    private fun arg(name: String, dir: String, v: String) =
        "<argument><name>$name</name><direction>$dir</direction><relatedStateVariable>$v</relatedStateVariable></argument>"

    private fun action(name: String, vararg args: String) = "<action><name>$name</name><argumentList>${args.joinToString("")}</argumentList></action>"

    private fun v(name: String, type: String, events: Boolean = false, vararg allowed: String) =
        "<stateVariable sendEvents=\"${if (events) "yes" else "no"}\"><name>$name</name><dataType>$type</dataType>" +
            (if (allowed.isEmpty()) "" else "<allowedValueList>${allowed.joinToString("") { "<allowedValue>$it</allowedValue>" }}</allowedValueList>") +
            "</stateVariable>"

    private fun scpd(actions: String, vars: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>$actions</actionList><serviceStateTable>$vars</serviceStateTable></scpd>"

    private const val I = "A_ARG_TYPE_InstanceID"
    private val id = arg("InstanceID", "in", I)

    val AVT_SCPD = scpd(
        action("SetAVTransportURI", id, arg("CurrentURI", "in", "AVTransportURI"), arg("CurrentURIMetaData", "in", "AVTransportURIMetaData")) +
            action("SetNextAVTransportURI", id, arg("NextURI", "in", "NextAVTransportURI"), arg("NextURIMetaData", "in", "NextAVTransportURIMetaData")) +
            action("GetMediaInfo", id, arg("NrTracks", "out", "NumberOfTracks"), arg("MediaDuration", "out", "CurrentMediaDuration"),
                arg("CurrentURI", "out", "AVTransportURI"), arg("CurrentURIMetaData", "out", "AVTransportURIMetaData"), arg("NextURI", "out", "NextAVTransportURI"),
                arg("NextURIMetaData", "out", "NextAVTransportURIMetaData"), arg("PlayMedium", "out", "PlaybackStorageMedium"),
                arg("RecordMedium", "out", "RecordStorageMedium"), arg("WriteStatus", "out", "RecordMediumWriteStatus")) +
            action("GetTransportInfo", id, arg("CurrentTransportState", "out", "TransportState"), arg("CurrentTransportStatus", "out", "TransportStatus"),
                arg("CurrentSpeed", "out", "TransportPlaySpeed")) +
            action("GetPositionInfo", id, arg("Track", "out", "CurrentTrack"), arg("TrackDuration", "out", "CurrentTrackDuration"),
                arg("TrackMetaData", "out", "CurrentTrackMetaData"), arg("TrackURI", "out", "CurrentTrackURI"), arg("RelTime", "out", "RelativeTimePosition"),
                arg("AbsTime", "out", "AbsoluteTimePosition"), arg("RelCount", "out", "RelativeCounterPosition"), arg("AbsCount", "out", "AbsoluteCounterPosition")) +
            action("GetDeviceCapabilities", id, arg("PlayMedia", "out", "PossiblePlaybackStorageMedia"), arg("RecMedia", "out", "PossibleRecordStorageMedia"),
                arg("RecQualityModes", "out", "PossibleRecordQualityModes")) +
            action("GetTransportSettings", id, arg("PlayMode", "out", "CurrentPlayMode"), arg("RecQualityMode", "out", "CurrentRecordQualityMode")) +
            action("GetCurrentTransportActions", id, arg("Actions", "out", "CurrentTransportActions")) +
            action("Stop", id) + action("Play", id, arg("Speed", "in", "TransportPlaySpeed")) + action("Pause", id) +
            action("Seek", id, arg("Unit", "in", "A_ARG_TYPE_SeekMode"), arg("Target", "in", "A_ARG_TYPE_SeekTarget")) +
            action("Next", id) + action("Previous", id),
        v("TransportState", "string", false, "STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT") +
            v("TransportStatus", "string", false, "OK", "ERROR_OCCURRED") + v("PlaybackStorageMedium", "string", false, "NONE", "NETWORK") +
            v("RecordStorageMedium", "string", false, "NOT_IMPLEMENTED") + v("PossiblePlaybackStorageMedia", "string") +
            v("PossibleRecordStorageMedia", "string") + v("CurrentPlayMode", "string", false, "NORMAL") + v("TransportPlaySpeed", "string", false, "1") +
            v("RecordMediumWriteStatus", "string", false, "NOT_IMPLEMENTED") + v("CurrentRecordQualityMode", "string", false, "NOT_IMPLEMENTED") +
            v("PossibleRecordQualityModes", "string") + v("NumberOfTracks", "ui4") + v("CurrentTrack", "ui4") + v("CurrentTrackDuration", "string") +
            v("CurrentMediaDuration", "string") + v("CurrentTrackMetaData", "string") + v("CurrentTrackURI", "string") + v("AVTransportURI", "string") +
            v("AVTransportURIMetaData", "string") + v("NextAVTransportURI", "string") + v("NextAVTransportURIMetaData", "string") +
            v("RelativeTimePosition", "string") + v("AbsoluteTimePosition", "string") + v("RelativeCounterPosition", "i4") +
            v("AbsoluteCounterPosition", "i4") + v("CurrentTransportActions", "string") + v("LastChange", "string", true) +
            v("A_ARG_TYPE_SeekMode", "string", false, "REL_TIME", "ABS_TIME", "TRACK_NR") + v("A_ARG_TYPE_SeekTarget", "string") + v(I, "ui4"),
    )

    val RC_SCPD = scpd(
        action("ListPresets", id, arg("CurrentPresetNameList", "out", "PresetNameList")) +
            action("SelectPreset", id, arg("PresetName", "in", "A_ARG_TYPE_PresetName")) +
            action("GetMute", id, arg("Channel", "in", "A_ARG_TYPE_Channel"), arg("CurrentMute", "out", "Mute")) +
            action("SetMute", id, arg("Channel", "in", "A_ARG_TYPE_Channel"), arg("DesiredMute", "in", "Mute")) +
            action("GetVolume", id, arg("Channel", "in", "A_ARG_TYPE_Channel"), arg("CurrentVolume", "out", "Volume")) +
            action("SetVolume", id, arg("Channel", "in", "A_ARG_TYPE_Channel"), arg("DesiredVolume", "in", "Volume")),
        v("PresetNameList", "string") + v("A_ARG_TYPE_PresetName", "string", false, "FactoryDefaults") + v("Mute", "boolean") +
            "<stateVariable sendEvents=\"no\"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>" +
            v("A_ARG_TYPE_Channel", "string", false, "Master") + v(I, "ui4") + v("LastChange", "string", true),
    )

    val CM_SCPD = scpd(
        action("GetProtocolInfo", arg("Source", "out", "SourceProtocolInfo"), arg("Sink", "out", "SinkProtocolInfo")) +
            action("GetCurrentConnectionIDs", arg("ConnectionIDs", "out", "CurrentConnectionIDs")) +
            action("GetCurrentConnectionInfo", arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID"), arg("RcsID", "out", "A_ARG_TYPE_RcsID"),
                arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID"), arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo"),
                arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager"), arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID"),
                arg("Direction", "out", "A_ARG_TYPE_Direction"), arg("Status", "out", "A_ARG_TYPE_ConnectionStatus")),
        v("SourceProtocolInfo", "string", true) + v("SinkProtocolInfo", "string", true) + v("CurrentConnectionIDs", "string", true) +
            v("A_ARG_TYPE_ConnectionStatus", "string", false, "OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown") +
            v("A_ARG_TYPE_ConnectionManager", "string") + v("A_ARG_TYPE_Direction", "string", false, "Input", "Output") +
            v("A_ARG_TYPE_ProtocolInfo", "string") + v("A_ARG_TYPE_ConnectionID", "i4") + v("A_ARG_TYPE_AVTransportID", "i4") + v("A_ARG_TYPE_RcsID", "i4"),
    )

    /** Argumento de entrada SOAP sin importar el prefijo de espacio de nombres. */
    fun input(body: String, name: String): String =
        Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?(?:/>|>(.*?)</(?:\\w+:)?$name>)", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unesc).orEmpty()

    fun response(service: String, action: String, outs: List<Pair<String, String>>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append("Response xmlns:u=\"").append(service).append("\">")
        for ((n, value) in outs) append('<').append(n).append('>').append(esc(value)).append("</").append(n).append('>')
        append("</u:").append(action).append("Response></s:Body></s:Envelope>")
    }

    fun fault(code: Int, desc: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
            "<errorCode>$code</errorCode><errorDescription>$desc</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"

    fun hms(sec: Double): String {
        val s = if (sec.isNaN() || sec.isInfinite() || sec < 0) 0L else sec.toLong()
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    fun parseHms(t: String): Double = t.trim().split(':').fold(0.0) { acc, p -> acc * 60 + (p.toDoubleOrNull() ?: 0.0) }

    fun mimeFromMetadata(didl: String) = Regex("protocolInfo=\"[^:\"]*:[^:\"]*:([^:\"]+)").find(didl)?.groupValues?.get(1).orEmpty()

    fun titleFromMetadata(didl: String) = Regex("<dc:title>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL).find(didl)?.groupValues?.get(1)?.let(::unesc).orEmpty()
}
