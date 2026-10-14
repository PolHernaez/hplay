using System;
using System.Collections.Generic;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace HPlayReceiver
{
    /// <summary>
    /// Receptor FCast v2 (protocolo abierto de FUTO, docs.fcast.org): TCP 46899, cabecera de 4 bytes (tamaño, LE) + 1 de
    /// opcode y cuerpo JSON. Lo usan Grayjay y los emisores FCast. Como el DLNA, solo recibe una URL y la reproduce.
    /// </summary>
    sealed class Fcast
    {
        public const int Port = 46899;
        const int MaxPacket = 512 * 1024;

        public interface IPlayer : Renderer.IPlayer
        {
            void LoadMedia(string url, string mime, string title, IDictionary<string, string> headers);
            /// <summary>Último estado del reproductor: posición, duración (s), PLAYING/PAUSED_PLAYBACK/STOPPED, volumen 0-100.</summary>
            void Status(out double pos, out double dur, out string state, out int vol);
        }

        readonly IPlayer player;
        readonly Func<IPAddress, bool> isLan;
        readonly JavaScriptSerializer json = new JavaScriptSerializer();
        TcpListener listener;
        volatile bool running = true;

        public Fcast(IPlayer player, Func<IPAddress, bool> isLan) { this.player = player; this.isLan = isLan; }

        public void Start()
        {
            try { listener = new TcpListener(IPAddress.Any, Port); listener.Start(); }
            catch (SocketException ex) { Program.Log("FCast: no s'ha pogut obrir el port " + Port + ": " + ex.Message); return; }
            Mdns.Register(Port);
            new Thread(() =>
            {
                while (running)
                {
                    TcpClient c;
                    try { c = listener.AcceptTcpClient(); } catch { break; }
                    new Thread(() => { try { using (c) Session(c); } catch { } }) { IsBackground = true }.Start();
                }
            }) { IsBackground = true, Name = "hplay-fcast" }.Start();
        }

        public void Stop()
        {
            running = false;
            Mdns.Unregister();
            try { listener?.Stop(); } catch { }
        }

        void Session(TcpClient c)
        {
            var remote = ((IPEndPoint)c.Client.RemoteEndPoint).Address;
            if (!isLan(remote)) return;
            Program.Log("FCast: connexió de " + remote);
            var st = c.GetStream();
            var sendLock = new object();
            void Send(int op, object body)
            {
                var b = body == null ? new byte[0] : Encoding.UTF8.GetBytes(json.Serialize(body));
                var p = new byte[5 + b.Length];
                BitConverter.GetBytes(b.Length + 1).CopyTo(p, 0); // Windows es little endian, como pide el protocolo
                p[4] = (byte)op;
                b.CopyTo(p, 5);
                lock (sendLock) st.Write(p, 0, p.Length);
            }
            Send(11, new Dictionary<string, object> { ["version"] = 2 });

            // Estado cada segundo mientras dure la conexión (el emisor pinta la barra de progreso con esto).
            var timer = new Timer(_ =>
            {
                try
                {
                    player.Status(out var pos, out var dur, out var state, out int _);
                    Send(6, new Dictionary<string, object>
                    {
                        ["generationTime"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                        ["time"] = pos, ["duration"] = dur, ["speed"] = 1.0,
                        ["state"] = state == "PLAYING" ? 1 : state == "PAUSED_PLAYBACK" ? 2 : 0,
                    });
                }
                catch { }
            }, null, 1000, 1000);
            try
            {
                var head = new byte[5];
                while (running)
                {
                    if (!ReadFull(st, head, 5)) break;
                    var size = BitConverter.ToInt32(head, 0);
                    if (size < 1 || size > MaxPacket) break; // el protocolo pide cortar
                    var body = new byte[size - 1];
                    if (!ReadFull(st, body, body.Length)) break;
                    var msg = body.Length == 0 ? null : json.Deserialize<Dictionary<string, object>>(Encoding.UTF8.GetString(body));
                    Handle(head[4], msg, Send);
                }
            }
            finally { timer.Dispose(); }
        }

        void Handle(int op, Dictionary<string, object> m, Action<int, object> send)
        {
            switch (op)
            {
                case 1: // Play
                    var url = Str(m, "url");
                    if (string.IsNullOrEmpty(url))
                    {
                        send(9, new Dictionary<string, object> { ["message"] = "HPlay: only url is supported" });
                        return;
                    }
                    var headers = new Dictionary<string, string>();
                    if (m.TryGetValue("headers", out var h) && h is Dictionary<string, object> hd)
                        foreach (var kv in hd) headers[kv.Key] = Convert.ToString(kv.Value, CultureInfo.InvariantCulture);
                    Program.Log("FCast Play " + url + " [capçaleres: " + string.Join(", ", headers.Keys) + "]"); // només noms: els valors poden ser credencials
                    player.LoadMedia(url, Str(m, "container"), "FCast", headers);
                    var t = Num(m, "time");
                    if (t > 0) new Timer(_ => player.Seek(t), null, 2500, Timeout.Infinite);
                    return;
                case 2: player.Pause(); return;
                case 3: player.Play(); return;
                case 4: player.Stop(); return;
                case 5: player.Seek(Num(m, "time")); return;
                case 8: player.SetVolume((int)Math.Round(Num(m, "volume") * 100)); return;
                case 12: send(13, null); return; // Ping → Pong
            }
        }

        static string Str(Dictionary<string, object> m, string k) => m != null && m.TryGetValue(k, out var v) ? v as string : null;
        static double Num(Dictionary<string, object> m, string k) =>
            m != null && m.TryGetValue(k, out var v) && v != null ? Convert.ToDouble(v, CultureInfo.InvariantCulture) : 0;

        static bool ReadFull(NetworkStream s, byte[] b, int n)
        {
            for (int r = 0; r < n;)
            {
                int k = s.Read(b, r, n - r);
                if (k <= 0) return false;
                r += k;
            }
            return true;
        }

        /// <summary>Anuncio mDNS «_fcast._tcp» con la API DNS-SD de Windows 10 1809+ (dnsapi.dll). Sin ella, se añade por IP.</summary>
        static class Mdns
        {
            [StructLayout(LayoutKind.Sequential)]
            struct DNS_SERVICE_REGISTER_REQUEST
            {
                public uint Version;
                public uint InterfaceIndex;
                public IntPtr pServiceInstance;
                public IntPtr pRegisterCompletionCallback;
                public IntPtr pQueryContext;
                public IntPtr hCredentials;
                [MarshalAs(UnmanagedType.Bool)] public bool unicastEnabled;
            }

            delegate void Completion(uint status, IntPtr ctx, IntPtr instance);

            [DllImport("dnsapi.dll", CharSet = CharSet.Unicode)]
            static extern IntPtr DnsServiceConstructInstance(string name, string host, IntPtr ip4, IntPtr ip6, ushort port, ushort priority, ushort weight, uint props, string[] keys, string[] values);
            [DllImport("dnsapi.dll")] static extern uint DnsServiceRegister(ref DNS_SERVICE_REGISTER_REQUEST req, IntPtr cancel);
            [DllImport("dnsapi.dll")] static extern uint DnsServiceDeRegister(ref DNS_SERVICE_REGISTER_REQUEST req, IntPtr cancel);

            static readonly Completion done = (s, c, i) => { }; // referencia viva: el sistema la llama más tarde
            static DNS_SERVICE_REGISTER_REQUEST req;
            static bool registered;

            public static void Register(int port)
            {
                try
                {
                    var host = Dns.GetHostName();
                    var inst = DnsServiceConstructInstance("HPlay " + host + "._fcast._tcp.local", host + ".local", IntPtr.Zero, IntPtr.Zero,
                        (ushort)port, 0, 0, 0, new string[0], new string[0]);
                    req = new DNS_SERVICE_REGISTER_REQUEST
                    {
                        Version = 1, // DNS_QUERY_REQUEST_VERSION1
                        pServiceInstance = inst,
                        pRegisterCompletionCallback = Marshal.GetFunctionPointerForDelegate(done),
                    };
                    var r = DnsServiceRegister(ref req, IntPtr.Zero);
                    registered = r == 9506; // DNS_REQUEST_PENDING
                    Program.Log("FCast mDNS: " + (registered ? "anunciat" : "error " + r));
                }
                catch (Exception ex) { Program.Log("FCast mDNS no disponible: " + ex.Message); }
            }

            public static void Unregister()
            {
                if (!registered) return;
                try { DnsServiceDeRegister(ref req, IntPtr.Zero); } catch { }
                registered = false;
            }
        }
    }
}
