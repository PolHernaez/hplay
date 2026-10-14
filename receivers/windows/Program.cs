using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace HPlayReceiver
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            // Las DLL de WebView2 viajan dentro del .exe: un único fichero portable.
            AppDomain.CurrentDomain.AssemblyResolve += (s, e) =>
            {
                var bytes = Resource(new AssemblyName(e.Name).Name + ".dll");
                return bytes == null ? null : Assembly.Load(bytes);
            };
            Run();
        }

        [MethodImpl(MethodImplOptions.NoInlining)] // los tipos de WebView2 se cargan después del AssemblyResolve
        static void Run()
        {
            var updated = Environment.GetCommandLineArgs().Contains("--updated");
            using (var single = new Mutex(false, "HPlayReceiver.Single"))
            {
                // Uno solo abierto (el puerto 8766 es suyo). Tras actualizar, se espera a que cierre el viejo.
                bool mine;
                try { mine = single.WaitOne(updated ? 15000 : 0); } catch (AbandonedMutexException) { mine = true; }
                if (!mine) return;
                AutoUpdate.CleanupOld();
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new ReceiverForm());
            }
        }

        static readonly object logGate = new object();
        public static string LogFile;

        /// <summary>Registro de diagnóstico (quién busca el receptor y qué le pide). Máx. ~1 MB, sin datos personales.</summary>
        public static void Log(string line)
        {
            if (LogFile == null) return;
            try
            {
                lock (logGate)
                {
                    var fi = new FileInfo(LogFile);
                    if (fi.Exists && fi.Length > 1_000_000) File.Delete(LogFile);
                    File.AppendAllText(LogFile, DateTime.Now.ToString("HH:mm:ss.fff") + " " + line + Environment.NewLine);
                }
            }
            catch { }
        }

        public static byte[] Resource(string name)
        {
            using (var st = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
            {
                if (st == null) return null;
                var ms = new MemoryStream();
                st.CopyTo(ms);
                return ms.ToArray();
            }
        }
    }

    /// <summary>
    /// Receptor HPlay para Windows:
    ///  1. Media Renderer DLNA: aparece en el botón «enviar a la tele» de las apps de vídeo y reproduce la URL que mandan.
    ///  2. Receptor HPlay (POST /open): la app HPlay del móvil le abre el duplicado de pantalla sin teclear nada.
    /// </summary>
    sealed class ReceiverForm : Form, Renderer.IPlayer
    {
        const int Port = 8766;
        static readonly IPAddress SsdpGroup = IPAddress.Parse("239.255.255.250");

        readonly Microsoft.Web.WebView2.WinForms.WebView2 web = new Microsoft.Web.WebView2.WinForms.WebView2();
        readonly string uuid;
        readonly string name = "HPlay · " + Environment.MachineName;
        readonly string dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HPlay");
        readonly bool es = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "es" || CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "ca";
        readonly Renderer renderer;
        volatile bool running = true;
        TcpListener http;
        UdpClient ssdp;
        System.Threading.Timer alive;
        bool fullscreen;

        public ReceiverForm()
        {
            Directory.CreateDirectory(dataDir);
            // UUID estable: los controladores DLNA recuerdan el aparato entre sesiones.
            var uuidFile = Path.Combine(dataDir, "uuid.txt");
            uuid = File.Exists(uuidFile) ? File.ReadAllText(uuidFile).Trim() : Guid.NewGuid().ToString();
            File.WriteAllText(uuidFile, uuid);
            renderer = new Renderer(this);
            Program.LogFile = Path.Combine(dataDir, "registre.txt");
            Program.Log("=== HPlay Receptor " + Application.ProductVersion + " · " + string.Join(", ", LocalIps()));

            Text = es ? "HPlay Receptor" : "HPlay Receiver";
            Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            BackColor = System.Drawing.Color.Black;
            Width = 1280;
            Height = 760;
            StartPosition = FormStartPosition.CenterScreen;
            KeyPreview = true;
            web.Dock = DockStyle.Fill;
            web.DefaultBackgroundColor = System.Drawing.Color.Black;
            Controls.Add(web);
            KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.F11) SetFullscreen(!fullscreen);
                else if (e.KeyCode == Keys.Escape) SetFullscreen(false);
            };
            Load += async (s, e) =>
            {
                try
                {
                    var loader = Path.Combine(dataDir, "WebView2Loader.dll");
                    var bytes = Program.Resource("WebView2Loader.dll");
                    if (!File.Exists(loader) || new FileInfo(loader).Length != bytes.Length) File.WriteAllBytes(loader, bytes);
                    Microsoft.Web.WebView2.Core.CoreWebView2Environment.SetLoaderDllFolderPath(dataDir);
                    // Ventana dedicada a reproducir lo que manda la red local:
                    //  - sonido sin pulsar nada;
                    //  - sin CORS, porque las apps mandan listas HLS de CDN que no lo permiten a otros orígenes.
                    var opts = new Microsoft.Web.WebView2.Core.CoreWebView2EnvironmentOptions("--autoplay-policy=no-user-gesture-required --disable-web-security");
                    var env = await Microsoft.Web.WebView2.Core.CoreWebView2Environment.CreateAsync(null, Path.Combine(dataDir, "webview"), opts);
                    await web.EnsureCoreWebView2Async(env);
                    web.CoreWebView2.Settings.AreDevToolsEnabled = false;
                    web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
                    web.CoreWebView2.ContainsFullScreenElementChanged += (o, a) => SetFullscreen(web.CoreWebView2.ContainsFullScreenElement);
                    web.CoreWebView2.WebMessageReceived += (o, a) => OnPlayerStatus(a.TryGetWebMessageAsString());
                    // La ventana solo muestra páginas de la red local: el reproductor propio y los receptores del móvil.
                    web.CoreWebView2.NavigationStarting += (o, a) =>
                    {
                        if (Uri.TryCreate(a.Uri, UriKind.Absolute, out var u) && u.Scheme == "http" &&
                            IPAddress.TryParse(u.Host, out var h) && IsLan(h)) return;
                        a.Cancel = true;
                    };
                    web.CoreWebView2.NewWindowRequested += (o, a) => a.Handled = true;
                }
                catch (Exception ex)
                {
                    MessageBox.Show((es ? "Falta Microsoft Edge WebView2 Runtime (viene con Windows 10/11).\n\n" : "Microsoft Edge WebView2 Runtime is missing (included in Windows 10/11).\n\n") + ex.Message, Text);
                    Close();
                    return;
                }
                new Thread(HttpLoop) { IsBackground = true, Name = "hplay-http" }.Start();
                new Thread(SsdpLoop) { IsBackground = true, Name = "hplay-ssdp" }.Start();
                ShowPlayer(null, null, null);
                AutoUpdate.Check(this, es);
            };
            FormClosing += (s, e) =>
            {
                running = false;
                alive?.Dispose();
                try { foreach (var ip in LocalIps()) Announce(ip, "ssdp:byebye"); } catch { }
                try { http?.Stop(); } catch { }
                try { ssdp?.Close(); } catch { }
            };
        }

        void SetFullscreen(bool on)
        {
            if (fullscreen == on) return;
            fullscreen = on;
            WindowState = FormWindowState.Normal; // si no, Windows no recalcula el tamaño sin bordes
            FormBorderStyle = on ? FormBorderStyle.None : FormBorderStyle.Sizable;
            if (on) WindowState = FormWindowState.Maximized;
        }

        void Ui(Action a)
        {
            if (IsDisposed || !IsHandleCreated) return;
            try { BeginInvoke(a); } catch (InvalidOperationException) { }
        }

        /// <summary>Página del reproductor (servida por el propio receptor). Con URL, la reproduce al cargar.</summary>
        void ShowPlayer(string url, string mime, string title)
        {
            var ips = string.Join(" · ", LocalIps().Select(a => a.ToString()));
            var q = "?name=" + Uri.EscapeDataString(name) + "&ips=" + Uri.EscapeDataString(ips) +
                    "&mime=" + Uri.EscapeDataString(mime ?? "") + "&title=" + Uri.EscapeDataString(title ?? "");
            web.CoreWebView2?.Navigate("http://127.0.0.1:" + Port + "/player" + q + (url == null ? "" : "#" + Uri.EscapeDataString(url)));
        }

        void Js(string script) => Ui(() => { web.CoreWebView2?.ExecuteScriptAsync(script); });

        // ---- Renderer.IPlayer: órdenes que llegan por DLNA ----

        public void LoadMedia(string url, string mime, string title) => Ui(() =>
        {
            ShowPlayer(url, mime, title);
            SetFullscreen(true);
            Activate();
        });

        public void Play() => Js("hplay.play()");
        public void Pause() => Js("hplay.pause()");
        public void Stop() => Js("hplay.stop()");
        public void Seek(double sec) => Js("hplay.seek(" + sec.ToString(CultureInfo.InvariantCulture) + ")");
        public void SetVolume(int v) => Js("hplay.volume(" + v + ")");
        public void SetMute(bool m) => Js("hplay.mute(" + (m ? "true" : "false") + ")");

        void OnPlayerStatus(string s)
        {
            var p = (s ?? "").Split('|');
            if (p.Length < 5) return;
            double.TryParse(p[0], NumberStyles.Float, CultureInfo.InvariantCulture, out var pos);
            double.TryParse(p[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var dur);
            int.TryParse(p[3], out var vol);
            renderer.Update(pos, dur, p[2], vol, p[4] == "1");
        }

        // ---- HTTP ----

        void HttpLoop()
        {
            // Tras una actualización el proceso viejo puede tardar un momento en soltar el puerto.
            for (int i = 0; ; i++)
            {
                try
                {
                    http = new TcpListener(IPAddress.Any, Port);
                    http.Start();
                    break;
                }
                catch (SocketException)
                {
                    if (i < 10) { Thread.Sleep(500); continue; }
                    var args = Environment.GetCommandLineArgs();
                    if (args.Contains("--updated") && !args.Contains("--respawned"))
                    {
                        // Lanzado por el actualizador de la v1.1, que nos pasó su socket abierto: se relanza limpio una vez.
                        Ui(() =>
                        {
                            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(Application.ExecutablePath, "--updated --respawned") { UseShellExecute = true });
                            Close();
                        });
                        return;
                    }
                    Ui(() => MessageBox.Show(es ? "El puerto 8766 está ocupado." : "Port 8766 is busy.", Text));
                    return;
                }
            }
            while (running)
            {
                TcpClient c;
                try { c = http.AcceptTcpClient(); } catch { break; }
                ThreadPool.QueueUserWorkItem(_ => { try { using (c) Serve(c); } catch { } });
            }
        }

        sealed class Req
        {
            public string Method, Path;
            public Dictionary<string, string> Headers = new Dictionary<string, string>();
            public byte[] Body = new byte[0];
        }

        /// <summary>Cabeceras byte a byte y cuerpo exacto: los metadatos DIDL traen acentos y Content-Length va en bytes.</summary>
        static Req Read(NetworkStream st)
        {
            var head = new MemoryStream();
            int b;
            while ((b = st.ReadByte()) >= 0)
            {
                head.WriteByte((byte)b);
                var n = (int)head.Length;
                var buf = head.GetBuffer();
                if (n >= 4 && buf[n - 4] == '\r' && buf[n - 3] == '\n' && buf[n - 2] == '\r' && buf[n - 1] == '\n') break;
                if (n > 16384) return null;
            }
            var lines = Encoding.UTF8.GetString(head.ToArray()).Split(new[] { "\r\n" }, StringSplitOptions.None);
            var first = lines[0].Split(' ');
            if (first.Length < 2) return null;
            var r = new Req { Method = first[0].ToUpperInvariant(), Path = first[1] };
            foreach (var l in lines.Skip(1))
            {
                var i = l.IndexOf(':');
                if (i > 0) r.Headers[l.Substring(0, i).Trim().ToLowerInvariant()] = l.Substring(i + 1).Trim();
            }
            if (r.Headers.TryGetValue("content-length", out var lenS) && int.TryParse(lenS, out var len) && len > 0 && len <= 1 << 20)
            {
                r.Body = new byte[len];
                int read = 0, k;
                while (read < len && (k = st.Read(r.Body, read, len - read)) > 0) read += k;
            }
            return r;
        }

        void Serve(TcpClient c)
        {
            var remote = ((IPEndPoint)c.Client.RemoteEndPoint).Address;
            if (!IsLan(remote)) { Program.Log("HTTP rebutjat (fora de la xarxa local) " + remote); return; }
            c.ReceiveTimeout = 8000;
            var st = c.GetStream();
            var req = Read(st);
            if (req == null) return;
            req.Headers.TryGetValue("user-agent", out var ua);
            req.Headers.TryGetValue("soapaction", out var soapLog);
            if (!IPAddress.IsLoopback(remote) || !req.Path.StartsWith("/player"))
                Program.Log("HTTP " + remote + " " + req.Method + " " + req.Path + (soapLog != null ? " " + soapLog : "") + " [" + ua + "]");

            void Reply(int code, string type, byte[] body, string extra = "")
            {
                var reason = code == 200 ? "OK" : code == 500 ? "Internal Server Error" : code == 412 ? "Precondition Failed" : code == 403 ? "Forbidden" : "Not Found";
                var h = Encoding.ASCII.GetBytes("HTTP/1.1 " + code + " " + reason + "\r\n" +
                    (type == null ? "" : "Content-Type: " + type + "\r\n") + "Content-Length: " + body.Length + "\r\n" +
                    "Server: Windows/10 UPnP/1.0 HPlay/1.0\r\nEXT:\r\nConnection: close\r\n" + extra + "\r\n");
                st.Write(h, 0, h.Length);
                if (req.Method != "HEAD") st.Write(body, 0, body.Length);
            }
            void Txt(int code, string type, string body, string extra = "") => Reply(code, type, Encoding.UTF8.GetBytes(body), extra);

            const string Xml = "text/xml; charset=\"utf-8\"";
            var path = req.Path.Split('?')[0];
            var last = path.Substring(path.LastIndexOf('/') + 1);
            switch (path)
            {
                case "/description.xml": Txt(200, Xml, Upnp.Description(uuid, name)); return;
                case "/avt.xml": Txt(200, Xml, Upnp.AvtScpd); return;
                case "/rc.xml": Txt(200, Xml, Upnp.RcScpd); return;
                case "/cm.xml": Txt(200, Xml, Upnp.CmScpd); return;
                case "/player":
                    if (!IPAddress.IsLoopback(remote)) break; // solo para la propia ventana
                    Reply(200, "text/html; charset=utf-8", Program.Resource("player.html"));
                    return;
                case "/hls.js":
                    if (!IPAddress.IsLoopback(remote)) break;
                    Reply(200, "application/javascript", Program.Resource("hls.js"));
                    return;
                case "/upnp/control/avt":
                case "/upnp/control/rc":
                case "/upnp/control/cm":
                    if (req.Method != "POST") break;
                    req.Headers.TryGetValue("soapaction", out var sa);
                    var action = (sa ?? "").Trim('"', ' ');
                    action = action.Substring(action.LastIndexOf('#') + 1);
                    var resp = renderer.Soap(last, action, Encoding.UTF8.GetString(req.Body), out var code);
                    if (code != 200) Program.Log("SOAP " + action + " -> error " + code);
                    Txt(code, Xml, resp);
                    return;
                case "/upnp/event/avt":
                case "/upnp/event/rc":
                case "/upnp/event/cm":
                    if (req.Method != "SUBSCRIBE" && req.Method != "UNSUBSCRIBE") break;
                    var hdr = renderer.Subscribe(req.Method, last, req.Headers);
                    if (hdr == null) Txt(412, null, "");
                    else Txt(200, null, "", hdr);
                    return;
                case "/open":
                    if (req.Method != "POST") break;
                    var url = Encoding.UTF8.GetString(req.Body).Trim();
                    // Solo receptores HPlay de la red local: nunca una web cualquiera de internet.
                    if (Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme == "http" && IPAddress.TryParse(u.Host, out var host) && IsLan(host))
                    {
                        Ui(() => { web.CoreWebView2?.Navigate(url); SetFullscreen(true); Activate(); });
                        Txt(200, "text/plain", "ok");
                    }
                    else Txt(403, "text/plain", "forbidden");
                    return;
            }
            Txt(404, "text/plain", "not found");
        }

        // ---- SSDP (UPnP 1.0 §1): respuestas a M-SEARCH y anuncios periódicos ----

        string[] Targets() => new[] { "upnp:rootdevice", "uuid:" + uuid, Upnp.Renderer, Upnp.AVT, Upnp.RC, Upnp.CM, Upnp.HPlayType };

        string Usn(string nt) => nt.StartsWith("uuid:") ? nt : "uuid:" + uuid + "::" + nt;

        void SsdpLoop()
        {
            try
            {
                ssdp = new UdpClient { ExclusiveAddressUse = false };
                // Windows ya tiene su servicio SSDP en el 1900: se comparte el puerto.
                ssdp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                ssdp.Client.Bind(new IPEndPoint(IPAddress.Any, 1900));
                foreach (var ip in LocalIps())
                {
                    try { ssdp.JoinMulticastGroup(SsdpGroup, ip); } catch { }
                }
            }
            catch (SocketException ex)
            {
                Program.Log("SSDP: no s'ha pogut obrir el port 1900: " + ex.Message);
                return; // sin SSDP el móvil aún puede añadir este PC por IP
            }
            // Anuncio al arrancar y cada 60 s: algunos controladores solo escuchan NOTIFY.
            alive = new System.Threading.Timer(_ => { foreach (var ip in LocalIps()) Announce(ip, "ssdp:alive"); }, null, 0, 60_000);
            while (running)
            {
                IPEndPoint from = null;
                byte[] data;
                try { data = ssdp.Receive(ref from); } catch { break; }
                var msg = Encoding.ASCII.GetString(data);
                if (!msg.StartsWith("M-SEARCH", StringComparison.OrdinalIgnoreCase)) continue;
                var st = msg.Split('\n').Select(l => l.Trim())
                    .FirstOrDefault(l => l.StartsWith("ST:", StringComparison.OrdinalIgnoreCase))?.Substring(3).Trim();
                if (st == null) continue;
                Program.Log("SSDP M-SEARCH de " + from.Address + " ST=" + st);
                var types = st == "ssdp:all" ? Targets() : Targets().Where(t => t.Equals(st, StringComparison.OrdinalIgnoreCase)).ToArray();
                if (types.Length == 0) continue;
                var local = LocalIpFor(from.Address);
                if (local == null) continue;
                foreach (var t in types)
                {
                    var b = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: " + DateTime.UtcNow.ToString("r") +
                        "\r\nEXT:\r\nLOCATION: http://" + local + ":" + Port + "/description.xml\r\nSERVER: Windows/10 UPnP/1.0 HPlay/1.0\r\nST: " + t +
                        "\r\nUSN: " + Usn(t) + "\r\nContent-Length: 0\r\n\r\n");
                    try { ssdp.Send(b, b.Length, from); } catch { }
                }
                NotifyBurst(local);
            }
        }

        long lastBurst;

        /// <summary>
        /// Tras una búsqueda, tres anuncios NOTIFY (0,7 s, 1,5 s y 3 s). Play Shots (Cling) borra su lista antes de
        /// cada búsqueda y en algunos móviles no vuelve a procesar las respuestas: así tiene un segundo camino.
        /// </summary>
        void NotifyBurst(IPAddress local)
        {
            var now = DateTime.UtcNow.Ticks / TimeSpan.TicksPerMillisecond;
            if (now - Interlocked.Read(ref lastBurst) < 5000) return;
            Interlocked.Exchange(ref lastBurst, now);
            ThreadPool.QueueUserWorkItem(_ =>
            {
                foreach (var wait in new[] { 700, 800, 1500 })
                {
                    Thread.Sleep(wait);
                    if (!running) return;
                    Announce(local, "ssdp:alive");
                }
            });
        }

        void Announce(IPAddress local, string nts)
        {
            foreach (var t in Targets())
            {
                var b = Encoding.ASCII.GetBytes("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://" + local + ":" + Port +
                    "/description.xml\r\nNT: " + t + "\r\nNTS: " + nts + "\r\nSERVER: Windows/10 UPnP/1.0 HPlay/1.0\r\nUSN: " + Usn(t) + "\r\n\r\n");
                try
                {
                    // Desde la IP de cada adaptador, para que el anuncio salga por la Wi-Fi/Ethernet correcta.
                    using (var u = new UdpClient(new IPEndPoint(local, 0))) { u.Send(b, b.Length, new IPEndPoint(SsdpGroup, 1900)); }
                }
                catch { }
            }
        }

        /// <summary>La IP de este PC por la que se llega al móvil (evita adaptadores virtuales de Hyper-V o VPN).</summary>
        static IPAddress LocalIpFor(IPAddress remote)
        {
            try
            {
                using (var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp))
                {
                    s.Connect(remote, 9);
                    return ((IPEndPoint)s.LocalEndPoint).Address;
                }
            }
            catch { return null; }
        }

        static List<IPAddress> LocalIps() =>
            NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Select(a => a.Address)
                .Where(a => a.AddressFamily == AddressFamily.InterNetwork && IsLan(a) && !IPAddress.IsLoopback(a))
                .ToList();

        public static bool IsLan(IPAddress a)
        {
            if (a.IsIPv4MappedToIPv6) a = a.MapToIPv4();
            if (IPAddress.IsLoopback(a)) return true;
            if (a.AddressFamily == AddressFamily.InterNetworkV6) return a.IsIPv6LinkLocal || (a.GetAddressBytes()[0] & 0xFE) == 0xFC;
            var b = a.GetAddressBytes();
            return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254);
        }
    }
}
