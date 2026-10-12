using System;
using System.Collections.Generic;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace HPlayReceiver
{
    /// <summary>
    /// Media Renderer DLNA: lo que hace que el PC aparezca en el botón «enviar a la tele» de las apps de vídeo
    /// (las que usan DLNA/UPnP, que son la mayoría de las que no son de Google). La app manda la URL y el PC la reproduce.
    /// </summary>
    sealed class Renderer
    {
        public interface IPlayer
        {
            void LoadMedia(string url, string mime, string title);
            void Play();
            void Pause();
            void Stop();
            void Seek(double sec);
            void SetVolume(int v);
            void SetMute(bool m);
        }

        readonly IPlayer player;
        readonly object gate = new object();
        string uri = "", meta = "";
        string state = "NO_MEDIA_PRESENT";
        double position, duration;
        int volume = 100;
        bool mute;

        public Renderer(IPlayer player) { this.player = player; }

        /// <summary>Estado que manda la página del reproductor cada medio segundo.</summary>
        public void Update(double pos, double dur, string st, int vol, bool muted)
        {
            bool avtChanged, rcChanged;
            lock (gate)
            {
                position = pos;
                duration = dur;
                avtChanged = st != state && uri.Length > 0;
                if (uri.Length > 0) state = st;
                rcChanged = vol != volume || muted != mute;
                volume = vol;
                mute = muted;
            }
            if (avtChanged) Notify("avt");
            if (rcChanged) Notify("rc");
        }

        public string Soap(string service, string action, string body, out int status)
        {
            status = 200;
            try
            {
                switch (service)
                {
                    case "avt": return Avt(action, body);
                    case "rc": return Rc(action, body);
                    case "cm": return Cm(action, body);
                }
            }
            catch (Exception) { }
            status = 500;
            return Upnp.Fault(401, "Invalid Action");
        }

        string Avt(string action, string body)
        {
            string R(params (string, string)[] o) => Upnp.Response(Upnp.AVT, action, o);
            lock (gate)
            {
                switch (action)
                {
                    case "SetAVTransportURI":
                        uri = Upnp.In(body, "CurrentURI").Trim();
                        meta = Upnp.In(body, "CurrentURIMetaData");
                        state = uri.Length > 0 ? "TRANSITIONING" : "NO_MEDIA_PRESENT";
                        position = duration = 0;
                        if (uri.Length > 0) player.LoadMedia(uri, Upnp.MimeFromMetadata(meta), Upnp.TitleFromMetadata(meta));
                        ThreadPool.QueueUserWorkItem(_ => Notify("avt"));
                        return R();
                    case "SetNextAVTransportURI":
                        return R();
                    case "Play":
                        if (uri.Length == 0) throw new InvalidOperationException();
                        player.Play();
                        return R();
                    case "Pause":
                        player.Pause();
                        return R();
                    case "Stop":
                        player.Stop();
                        state = uri.Length > 0 ? "STOPPED" : "NO_MEDIA_PRESENT";
                        ThreadPool.QueueUserWorkItem(_ => Notify("avt"));
                        return R();
                    case "Seek":
                        var unit = Upnp.In(body, "Unit");
                        if (unit == "REL_TIME" || unit == "ABS_TIME") player.Seek(Upnp.ParseHms(Upnp.In(body, "Target")));
                        return R();
                    case "Next":
                    case "Previous":
                        return R();
                    case "GetTransportInfo":
                        return R(("CurrentTransportState", state), ("CurrentTransportStatus", "OK"), ("CurrentSpeed", "1"));
                    case "GetPositionInfo":
                        return R(("Track", uri.Length > 0 ? "1" : "0"), ("TrackDuration", Upnp.Hms(duration)), ("TrackMetaData", meta),
                            ("TrackURI", uri), ("RelTime", Upnp.Hms(position)), ("AbsTime", Upnp.Hms(position)),
                            ("RelCount", "2147483647"), ("AbsCount", "2147483647"));
                    case "GetMediaInfo":
                        return R(("NrTracks", uri.Length > 0 ? "1" : "0"), ("MediaDuration", Upnp.Hms(duration)), ("CurrentURI", uri),
                            ("CurrentURIMetaData", meta), ("NextURI", ""), ("NextURIMetaData", ""), ("PlayMedium", uri.Length > 0 ? "NETWORK" : "NONE"),
                            ("RecordMedium", "NOT_IMPLEMENTED"), ("WriteStatus", "NOT_IMPLEMENTED"));
                    case "GetDeviceCapabilities":
                        return R(("PlayMedia", "NETWORK"), ("RecMedia", "NOT_IMPLEMENTED"), ("RecQualityModes", "NOT_IMPLEMENTED"));
                    case "GetTransportSettings":
                        return R(("PlayMode", "NORMAL"), ("RecQualityMode", "NOT_IMPLEMENTED"));
                    case "GetCurrentTransportActions":
                        return R(("Actions", state == "PLAYING" ? "Pause,Stop,Seek" : uri.Length > 0 ? "Play,Stop,Seek" : ""));
                }
            }
            throw new InvalidOperationException(action);
        }

        string Rc(string action, string body)
        {
            string R(params (string, string)[] o) => Upnp.Response(Upnp.RC, action, o);
            switch (action)
            {
                case "GetVolume": lock (gate) return R(("CurrentVolume", volume.ToString(CultureInfo.InvariantCulture)));
                case "SetVolume":
                    int.TryParse(Upnp.In(body, "DesiredVolume"), out var v);
                    v = Math.Max(0, Math.Min(100, v));
                    lock (gate) volume = v;
                    player.SetVolume(v);
                    ThreadPool.QueueUserWorkItem(_ => Notify("rc"));
                    return R();
                case "GetMute": lock (gate) return R(("CurrentMute", mute ? "1" : "0"));
                case "SetMute":
                    var m = Upnp.In(body, "DesiredMute");
                    var on = m == "1" || m.Equals("true", StringComparison.OrdinalIgnoreCase);
                    lock (gate) mute = on;
                    player.SetMute(on);
                    ThreadPool.QueueUserWorkItem(_ => Notify("rc"));
                    return R();
                case "ListPresets": return R(("CurrentPresetNameList", "FactoryDefaults"));
                case "SelectPreset": return R();
            }
            throw new InvalidOperationException(action);
        }

        string Cm(string action, string body)
        {
            string R(params (string, string)[] o) => Upnp.Response(Upnp.CM, action, o);
            switch (action)
            {
                case "GetProtocolInfo": return R(("Source", ""), ("Sink", Upnp.SinkProtocolInfo));
                case "GetCurrentConnectionIDs": return R(("ConnectionIDs", "0"));
                case "GetCurrentConnectionInfo":
                    return R(("RcsID", "0"), ("AVTransportID", "0"), ("ProtocolInfo", ""), ("PeerConnectionManager", ""),
                        ("PeerConnectionID", "-1"), ("Direction", "Input"), ("Status", "OK"));
            }
            throw new InvalidOperationException(action);
        }

        // ---- GENA: eventos para los controladores que se suscriben (LastChange) ----

        sealed class Sub { public string Sid, Callback, Service; public int Seq; public DateTime Expires; }
        readonly List<Sub> subs = new List<Sub>();

        /// <summary>SUBSCRIBE / UNSUBSCRIBE. Devuelve las cabeceras de respuesta o null si la petición no vale.</summary>
        public string Subscribe(string method, string service, Dictionary<string, string> h)
        {
            h.TryGetValue("sid", out var sid);
            lock (subs)
            {
                subs.RemoveAll(s => s.Expires < DateTime.UtcNow);
                if (method == "UNSUBSCRIBE") { subs.RemoveAll(s => s.Sid == sid); return ""; }
                Sub sub;
                if (!string.IsNullOrEmpty(sid))
                {
                    sub = subs.Find(s => s.Sid == sid);
                    if (sub == null) return null;
                }
                else
                {
                    if (!h.TryGetValue("callback", out var cb)) return null;
                    var start = cb.IndexOf('<');
                    var end = cb.IndexOf('>', start + 1);
                    if (start < 0 || end < 0) return null;
                    var url = cb.Substring(start + 1, end - start - 1);
                    if (!Uri.TryCreate(url, UriKind.Absolute, out var u) || u.Scheme != "http") return null;
                    sub = new Sub { Sid = "uuid:" + Guid.NewGuid(), Callback = url, Service = service };
                    subs.Add(sub);
                    var initial = sub;
                    ThreadPool.QueueUserWorkItem(_ => { Thread.Sleep(200); Send(initial); }); // evento inicial obligatorio
                }
                sub.Expires = DateTime.UtcNow.AddSeconds(1800);
                return "SID: " + sub.Sid + "\r\nTIMEOUT: Second-1800\r\n";
            }
        }

        void Notify(string service)
        {
            Sub[] targets;
            lock (subs) targets = subs.FindAll(s => s.Service == service && s.Expires > DateTime.UtcNow).ToArray();
            foreach (var s in targets) Send(s);
        }

        string EventBody(string service)
        {
            string inner;
            lock (gate)
            {
                if (service == "avt")
                    inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">" +
                            "<TransportState val=\"" + state + "\"/><TransportStatus val=\"OK\"/><AVTransportURI val=\"" + WebUtility.HtmlEncode(uri) + "\"/>" +
                            "<CurrentTrackDuration val=\"" + Upnp.Hms(duration) + "\"/><CurrentTransportActions val=\"Play,Pause,Stop,Seek\"/></InstanceID></Event>";
                else if (service == "rc")
                    inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
                            "<Volume channel=\"Master\" val=\"" + volume + "\"/><Mute channel=\"Master\" val=\"" + (mute ? 1 : 0) + "\"/></InstanceID></Event>";
                else
                    return "<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
                           "<e:property><SourceProtocolInfo></SourceProtocolInfo></e:property>" +
                           "<e:property><SinkProtocolInfo>" + Upnp.SinkProtocolInfo + "</SinkProtocolInfo></e:property>" +
                           "<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property></e:propertyset>";
            }
            return "<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange>" +
                   WebUtility.HtmlEncode(inner) + "</LastChange></e:property></e:propertyset>";
        }

        void Send(Sub s)
        {
            try
            {
                var u = new Uri(s.Callback);
                if (!IPAddress.TryParse(u.Host, out var host) || !ReceiverForm.IsLan(host)) return;
                int seq;
                lock (subs) seq = s.Seq++;
                var body = Encoding.UTF8.GetBytes(EventBody(s.Service));
                var head = Encoding.ASCII.GetBytes("NOTIFY " + u.PathAndQuery + " HTTP/1.1\r\nHOST: " + u.Authority +
                    "\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: " + s.Sid +
                    "\r\nSEQ: " + seq + "\r\nCONTENT-LENGTH: " + body.Length + "\r\nConnection: close\r\n\r\n");
                using (var c = new TcpClient())
                {
                    if (!c.ConnectAsync(host, u.Port).Wait(2000)) return;
                    var st = c.GetStream();
                    st.Write(head, 0, head.Length);
                    st.Write(body, 0, body.Length);
                    st.ReadTimeout = 2000;
                    try { st.ReadByte(); } catch { }
                }
            }
            catch { }
        }
    }
}
