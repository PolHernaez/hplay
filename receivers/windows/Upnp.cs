using System;
using System.Globalization;
using System.Net;
using System.Text.RegularExpressions;

namespace HPlayReceiver
{
    /// <summary>Textos fijos de UPnP AV 1.0 (MediaRenderer:1) y utilidades SOAP.</summary>
    static class Upnp
    {
        public const string AVT = "urn:schemas-upnp-org:service:AVTransport:1";
        public const string RC = "urn:schemas-upnp-org:service:RenderingControl:1";
        public const string CM = "urn:schemas-upnp-org:service:ConnectionManager:1";
        public const string Renderer = "urn:schemas-upnp-org:device:MediaRenderer:1";
        public const string HPlayType = "urn:hplay-org:device:Receiver:1";

        /// <summary>Formatos que reproduce WebView2 (Chromium) directamente o con hls.js.</summary>
        public static readonly string SinkProtocolInfo = string.Join(",", new[]
        {
            "http-get:*:video/mp4:*", "http-get:*:video/x-m4v:*", "http-get:*:video/webm:*", "http-get:*:video/x-matroska:*",
            "http-get:*:application/vnd.apple.mpegurl:*", "http-get:*:application/x-mpegurl:*", "http-get:*:audio/mpegurl:*",
            "http-get:*:video/quicktime:*", "http-get:*:audio/mpeg:*", "http-get:*:audio/mp4:*", "http-get:*:audio/aac:*",
            "http-get:*:audio/ogg:*", "http-get:*:audio/wav:*", "http-get:*:audio/flac:*",
        });

        public static string Description(string uuid, string name) =>
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><device>" +
            "<deviceType>" + Renderer + "</deviceType>" +
            "<friendlyName>" + WebUtility.HtmlEncode(name) + "</friendlyName>" +
            "<manufacturer>HPlay</manufacturer><manufacturerURL>https://github.com/PolHernaez/hplay</manufacturerURL>" +
            "<modelDescription>HPlay Receiver</modelDescription><modelName>HPlay Receiver</modelName><modelNumber>1.0</modelNumber>" +
            "<UDN>uuid:" + uuid + "</UDN><dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>" +
            Service(AVT, "AVTransport", "avt") + Service(RC, "RenderingControl", "rc") + Service(CM, "ConnectionManager", "cm") +
            "</serviceList></device></root>";

        static string Service(string type, string id, string path) =>
            "<service><serviceType>" + type + "</serviceType><serviceId>urn:upnp-org:serviceId:" + id + "</serviceId>" +
            "<SCPDURL>/" + path + ".xml</SCPDURL><controlURL>/upnp/control/" + path + "</controlURL>" +
            "<eventSubURL>/upnp/event/" + path + "</eventSubURL></service>";

        // ---- SCPD (descripción de servicios) ----

        static string Arg(string name, string dir, string var) =>
            "<argument><name>" + name + "</name><direction>" + dir + "</direction><relatedStateVariable>" + var + "</relatedStateVariable></argument>";

        static string Action(string name, params string[] args) =>
            "<action><name>" + name + "</name><argumentList>" + string.Concat(args) + "</argumentList></action>";

        static string Var(string name, string type, bool events = false, params string[] allowed) =>
            "<stateVariable sendEvents=\"" + (events ? "yes" : "no") + "\"><name>" + name + "</name><dataType>" + type + "</dataType>" +
            (allowed.Length == 0 ? "" : "<allowedValueList>" + string.Concat(Array.ConvertAll(allowed, a => "<allowedValue>" + a + "</allowedValue>")) + "</allowedValueList>") +
            "</stateVariable>";

        static string Scpd(string actions, string vars) =>
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>" + actions + "</actionList><serviceStateTable>" + vars + "</serviceStateTable></scpd>";

        const string I = "A_ARG_TYPE_InstanceID";

        public static readonly string AvtScpd = Scpd(
            Action("SetAVTransportURI", Arg("InstanceID", "in", I), Arg("CurrentURI", "in", "AVTransportURI"), Arg("CurrentURIMetaData", "in", "AVTransportURIMetaData")) +
            Action("SetNextAVTransportURI", Arg("InstanceID", "in", I), Arg("NextURI", "in", "NextAVTransportURI"), Arg("NextURIMetaData", "in", "NextAVTransportURIMetaData")) +
            Action("GetMediaInfo", Arg("InstanceID", "in", I), Arg("NrTracks", "out", "NumberOfTracks"), Arg("MediaDuration", "out", "CurrentMediaDuration"),
                Arg("CurrentURI", "out", "AVTransportURI"), Arg("CurrentURIMetaData", "out", "AVTransportURIMetaData"), Arg("NextURI", "out", "NextAVTransportURI"),
                Arg("NextURIMetaData", "out", "NextAVTransportURIMetaData"), Arg("PlayMedium", "out", "PlaybackStorageMedium"),
                Arg("RecordMedium", "out", "RecordStorageMedium"), Arg("WriteStatus", "out", "RecordMediumWriteStatus")) +
            Action("GetTransportInfo", Arg("InstanceID", "in", I), Arg("CurrentTransportState", "out", "TransportState"),
                Arg("CurrentTransportStatus", "out", "TransportStatus"), Arg("CurrentSpeed", "out", "TransportPlaySpeed")) +
            Action("GetPositionInfo", Arg("InstanceID", "in", I), Arg("Track", "out", "CurrentTrack"), Arg("TrackDuration", "out", "CurrentTrackDuration"),
                Arg("TrackMetaData", "out", "CurrentTrackMetaData"), Arg("TrackURI", "out", "CurrentTrackURI"), Arg("RelTime", "out", "RelativeTimePosition"),
                Arg("AbsTime", "out", "AbsoluteTimePosition"), Arg("RelCount", "out", "RelativeCounterPosition"), Arg("AbsCount", "out", "AbsoluteCounterPosition")) +
            Action("GetDeviceCapabilities", Arg("InstanceID", "in", I), Arg("PlayMedia", "out", "PossiblePlaybackStorageMedia"),
                Arg("RecMedia", "out", "PossibleRecordStorageMedia"), Arg("RecQualityModes", "out", "PossibleRecordQualityModes")) +
            Action("GetTransportSettings", Arg("InstanceID", "in", I), Arg("PlayMode", "out", "CurrentPlayMode"), Arg("RecQualityMode", "out", "CurrentRecordQualityMode")) +
            Action("GetCurrentTransportActions", Arg("InstanceID", "in", I), Arg("Actions", "out", "CurrentTransportActions")) +
            Action("Stop", Arg("InstanceID", "in", I)) +
            Action("Play", Arg("InstanceID", "in", I), Arg("Speed", "in", "TransportPlaySpeed")) +
            Action("Pause", Arg("InstanceID", "in", I)) +
            Action("Seek", Arg("InstanceID", "in", I), Arg("Unit", "in", "A_ARG_TYPE_SeekMode"), Arg("Target", "in", "A_ARG_TYPE_SeekTarget")) +
            Action("Next", Arg("InstanceID", "in", I)) +
            Action("Previous", Arg("InstanceID", "in", I)),
            Var("TransportState", "string", false, "STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT") +
            Var("TransportStatus", "string", false, "OK", "ERROR_OCCURRED") + Var("PlaybackStorageMedium", "string", false, "NONE", "NETWORK") +
            Var("RecordStorageMedium", "string", false, "NOT_IMPLEMENTED") + Var("PossiblePlaybackStorageMedia", "string") +
            Var("PossibleRecordStorageMedia", "string") + Var("CurrentPlayMode", "string", false, "NORMAL") + Var("TransportPlaySpeed", "string", false, "1") +
            Var("RecordMediumWriteStatus", "string", false, "NOT_IMPLEMENTED") + Var("CurrentRecordQualityMode", "string", false, "NOT_IMPLEMENTED") +
            Var("PossibleRecordQualityModes", "string") + Var("NumberOfTracks", "ui4") + Var("CurrentTrack", "ui4") + Var("CurrentTrackDuration", "string") +
            Var("CurrentMediaDuration", "string") + Var("CurrentTrackMetaData", "string") + Var("CurrentTrackURI", "string") + Var("AVTransportURI", "string") +
            Var("AVTransportURIMetaData", "string") + Var("NextAVTransportURI", "string") + Var("NextAVTransportURIMetaData", "string") +
            Var("RelativeTimePosition", "string") + Var("AbsoluteTimePosition", "string") + Var("RelativeCounterPosition", "i4") +
            Var("AbsoluteCounterPosition", "i4") + Var("CurrentTransportActions", "string") + Var("LastChange", "string", true) +
            Var("A_ARG_TYPE_SeekMode", "string", false, "REL_TIME", "ABS_TIME", "TRACK_NR") + Var("A_ARG_TYPE_SeekTarget", "string") + Var(I, "ui4"));

        public static readonly string RcScpd = Scpd(
            Action("ListPresets", Arg("InstanceID", "in", I), Arg("CurrentPresetNameList", "out", "PresetNameList")) +
            Action("SelectPreset", Arg("InstanceID", "in", I), Arg("PresetName", "in", "A_ARG_TYPE_PresetName")) +
            Action("GetMute", Arg("InstanceID", "in", I), Arg("Channel", "in", "A_ARG_TYPE_Channel"), Arg("CurrentMute", "out", "Mute")) +
            Action("SetMute", Arg("InstanceID", "in", I), Arg("Channel", "in", "A_ARG_TYPE_Channel"), Arg("DesiredMute", "in", "Mute")) +
            Action("GetVolume", Arg("InstanceID", "in", I), Arg("Channel", "in", "A_ARG_TYPE_Channel"), Arg("CurrentVolume", "out", "Volume")) +
            Action("SetVolume", Arg("InstanceID", "in", I), Arg("Channel", "in", "A_ARG_TYPE_Channel"), Arg("DesiredVolume", "in", "Volume")),
            Var("PresetNameList", "string") + Var("A_ARG_TYPE_PresetName", "string", false, "FactoryDefaults") + Var("Mute", "boolean") +
            "<stateVariable sendEvents=\"no\"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>" +
            Var("A_ARG_TYPE_Channel", "string", false, "Master") + Var(I, "ui4") + Var("LastChange", "string", true));

        public static readonly string CmScpd = Scpd(
            Action("GetProtocolInfo", Arg("Source", "out", "SourceProtocolInfo"), Arg("Sink", "out", "SinkProtocolInfo")) +
            Action("GetCurrentConnectionIDs", Arg("ConnectionIDs", "out", "CurrentConnectionIDs")) +
            Action("GetCurrentConnectionInfo", Arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID"), Arg("RcsID", "out", "A_ARG_TYPE_RcsID"),
                Arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID"), Arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo"),
                Arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager"), Arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID"),
                Arg("Direction", "out", "A_ARG_TYPE_Direction"), Arg("Status", "out", "A_ARG_TYPE_ConnectionStatus")),
            Var("SourceProtocolInfo", "string", true) + Var("SinkProtocolInfo", "string", true) + Var("CurrentConnectionIDs", "string", true) +
            Var("A_ARG_TYPE_ConnectionStatus", "string", false, "OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown") +
            Var("A_ARG_TYPE_ConnectionManager", "string") + Var("A_ARG_TYPE_Direction", "string", false, "Input", "Output") +
            Var("A_ARG_TYPE_ProtocolInfo", "string") + Var("A_ARG_TYPE_ConnectionID", "i4") + Var("A_ARG_TYPE_AVTransportID", "i4") + Var("A_ARG_TYPE_RcsID", "i4"));

        // ---- SOAP ----

        /// <summary>Argumento de entrada de una petición SOAP (sin importar el prefijo de espacio de nombres).</summary>
        public static string In(string body, string name)
        {
            var m = Regex.Match(body, "<(?:\\w+:)?" + name + "(?:\\s[^>]*)?(?:/>|>(.*?)</(?:\\w+:)?" + name + ">)", RegexOptions.Singleline);
            return m.Success ? WebUtility.HtmlDecode(m.Groups[1].Value) : "";
        }

        public static string Response(string service, string action, params (string name, string value)[] outs)
        {
            var sb = new System.Text.StringBuilder();
            sb.Append("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>");
            sb.Append("<u:").Append(action).Append("Response xmlns:u=\"").Append(service).Append("\">");
            foreach (var (n, v) in outs) sb.Append('<').Append(n).Append('>').Append(WebUtility.HtmlEncode(v ?? "")).Append("</").Append(n).Append('>');
            sb.Append("</u:").Append(action).Append("Response></s:Body></s:Envelope>");
            return sb.ToString();
        }

        public static string Fault(int code, string desc) =>
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
            "<errorCode>" + code + "</errorCode><errorDescription>" + desc + "</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>";

        public static string Hms(double sec)
        {
            if (double.IsNaN(sec) || sec < 0 || double.IsInfinity(sec)) sec = 0;
            var s = (long)sec;
            return string.Format(CultureInfo.InvariantCulture, "{0}:{1:00}:{2:00}", s / 3600, s / 60 % 60, s % 60);
        }

        /// <summary>«1:02:03», «01:02:03.500» o segundos sueltos.</summary>
        public static double ParseHms(string t)
        {
            if (string.IsNullOrWhiteSpace(t)) return 0;
            double total = 0;
            foreach (var p in t.Trim().Split(':'))
            {
                double.TryParse(p, NumberStyles.Float, CultureInfo.InvariantCulture, out var v);
                total = total * 60 + v;
            }
            return total;
        }

        /// <summary>MIME del &lt;res protocolInfo="http-get:*:MIME:…"&gt; de los metadatos DIDL-Lite.</summary>
        public static string MimeFromMetadata(string didl)
        {
            var m = Regex.Match(didl ?? "", "protocolInfo=\"[^:\"]*:[^:\"]*:([^:\"]+)");
            return m.Success ? m.Groups[1].Value : "";
        }

        public static string TitleFromMetadata(string didl)
        {
            var m = Regex.Match(didl ?? "", "<dc:title>(.*?)</dc:title>", RegexOptions.Singleline);
            return m.Success ? WebUtility.HtmlDecode(m.Groups[1].Value) : "";
        }
    }
}
