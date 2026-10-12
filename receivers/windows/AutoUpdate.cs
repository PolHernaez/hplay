using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace HPlayReceiver
{
    /// <summary>
    /// Actualizador del .exe. Misma fuente y mismas garantías que el de Android:
    ///  - update.json del último release + update.json.sig (RSA-SHA256 con la clave privada de HPlay-claus);
    ///    sin firma válida no se descarga nada, aunque alguien controlara la cuenta de GitHub.
    ///  - 4 intentos con espera creciente, reanudación con Range, tamaño exacto y SHA-256 del manifiesto firmado.
    ///  - Sustitución: el .exe en marcha se renombra a .old (Windows lo permite), el nuevo ocupa su sitio y se reinicia.
    /// Las compilaciones sin número de versión (revisión 0: desarrollo, CI) no se actualizan.
    /// </summary>
    static class AutoUpdate
    {
        const string Base = "https://github.com/PolHernaez/hplay/releases/latest/download/";
        const string Asset = "HPlay-Receptor.exe";
        const int Attempts = 4;

        /// <summary>Clave pública de tools/update-public.pem (módulo RSA 3072, exponente 65537).</summary>
        const string Modulus =
            "9DOhVLEt3KmMsYLpoq/TNef9kBmFl5CRxhZtQ94nxLOELlZCq/M7m1eglau0hXAJ8SKDin7XXwSpBorwnzKmZQll5XvjyYgRtR91UW6YK0uSwy8M9sFOiVp0Xy2veq9se3ItPilD" +
            "WBLa27yknrBYlEn9kLry2h439rdQOY5yZ7uD9uVAbgElEN5jMXsyK7RgZNo/VYbRAziE02p/MTAdrRVhGYTN9w7XS58xLlZEsFutzBCJ0uiRn0FZ/luyADbMu1du3QKCNXfCdNOi" +
            "RnO+7/toaRK9yxvEEM9Sg+T3LenMCFwq+Zhj60La5BepzQNpUnnXXyprv4Mq9lKWhA4aMNkPcRMBGvfk2OpUbrpX2PQ/UzyvGQV9VaaO4Y6G4eYtcNu+Sm+lp7JqNhRmffPOMZ4G" +
            "C8Te+pnRK83b9oThNKv3ggQeoa/S4i2J7TiODGuJjhEFiq8iL0taorsCTjc57n3AqrCDXj2hkhlJ43mNrl/T2OBlYqZajyhJnACHIRKz";

        sealed class Manifest { public string Version; public long Code, MinRequired, Size; public string Sha256, Notes; }

        static int InstalledCode => Assembly.GetExecutingAssembly().GetName().Version.Revision;
        static string InstalledName { get { var v = Assembly.GetExecutingAssembly().GetName().Version; return v.Major + "." + v.Minor; } }

        /// <summary>Borra el .exe viejo que dejó la última actualización (ya no está en uso).</summary>
        public static void CleanupOld()
        {
            var old = Application.ExecutablePath + ".old";
            for (int i = 0; i < 10 && File.Exists(old); i++)
            {
                try { File.Delete(old); } catch { System.Threading.Thread.Sleep(500); }
            }
        }

        public static void Check(Form owner, bool es)
        {
            if (InstalledCode <= 0) return;
            Task.Run(() =>
            {
                Manifest m;
                try { m = Fetch(); } catch { return; } // sin red o firma mala: se sigue sin molestar
                if (m.Code <= InstalledCode) return;
                owner.BeginInvoke((Action)(() => Ask(owner, m, es)));
            });
        }

        static byte[] Get(string url)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.UserAgent = "HPlay-Receptor/" + InstalledName;
            req.Timeout = 10000;
            req.ReadWriteTimeout = 15000;
            using (var resp = (HttpWebResponse)req.GetResponse())
            using (var st = resp.GetResponseStream())
            using (var ms = new MemoryStream())
            {
                st.CopyTo(ms);
                return ms.ToArray();
            }
        }

        static Manifest Fetch()
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            var json = Get(Base + "update.json");
            var sig = Get(Base + "update.json.sig");
            using (var rsa = new RSACng())
            {
                rsa.ImportParameters(new RSAParameters { Modulus = Convert.FromBase64String(Modulus), Exponent = new byte[] { 1, 0, 1 } });
                if (!rsa.VerifyData(json, sig, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1)) throw new CryptographicException("bad_signature");
            }
            var o = (Dictionary<string, object>)new JavaScriptSerializer().DeserializeObject(System.Text.Encoding.UTF8.GetString(json));
            var files = (Dictionary<string, object>)o["files"];
            var f = (Dictionary<string, object>)files[Asset];
            return new Manifest
            {
                Version = (string)o["version"],
                Code = Convert.ToInt64(o["versionCode"]),
                MinRequired = o.ContainsKey("minRequired") ? Convert.ToInt64(o["minRequired"]) : 0,
                Notes = o.ContainsKey("notes") ? o["notes"] as string ?? "" : "",
                Size = Convert.ToInt64(f["size"]),
                Sha256 = ((string)f["sha256"]).ToLowerInvariant(),
            };
        }

        static void Ask(Form owner, Manifest m, bool es)
        {
            var forced = InstalledCode < m.MinRequired;
            var text = forced
                ? (es ? "Esta versión de HPlay Receptor ya no es compatible. Hay que actualizar a la " + m.Version + "."
                      : "This version of HPlay Receiver is no longer supported. Please update to " + m.Version + ".")
                : (es ? "Hay una versión nueva de HPlay Receptor: " + m.Version + " (tienes la " + InstalledName + ").\n\n¿Actualizar ahora?"
                      : "A new version of HPlay Receiver is available: " + m.Version + " (you have " + InstalledName + ").\n\nUpdate now?");
            if (!string.IsNullOrWhiteSpace(m.Notes)) text += "\n\n" + m.Notes;
            var r = MessageBox.Show(owner, text, "HPlay " + m.Version, forced ? MessageBoxButtons.OKCancel : MessageBoxButtons.YesNo, MessageBoxIcon.Information);
            if (r == DialogResult.OK || r == DialogResult.Yes) Run(owner, m, es);
            else if (forced) owner.Close();
        }

        static void Run(Form owner, Manifest m, bool es)
        {
            var dialog = new Form
            {
                Text = "HPlay " + m.Version, Width = 420, Height = 140, FormBorderStyle = FormBorderStyle.FixedDialog,
                StartPosition = FormStartPosition.CenterParent, MaximizeBox = false, MinimizeBox = false, ControlBox = false,
            };
            var label = new Label { Left = 16, Top = 14, Width = 380, Text = es ? "Descargando…" : "Downloading…" };
            var bar = new ProgressBar { Left = 16, Top = 44, Width = 370, Height = 22, Maximum = 100 };
            dialog.Controls.Add(label);
            dialog.Controls.Add(bar);
            dialog.Shown += (s, e) => Task.Run(() =>
            {
                var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HPlay", "update");
                var file = Path.Combine(dir, Asset);
                try
                {
                    Download(m, file, (pct, attempt) => dialog.BeginInvoke((Action)(() =>
                    {
                        bar.Value = Math.Max(0, Math.Min(100, pct));
                        label.Text = attempt > 1 && pct == 0
                            ? (es ? "Problema de conexión, reintentando (" : "Connection problem, retrying (") + attempt + "/" + Attempts + ")…"
                            : (es ? "Descargando… " : "Downloading… ") + pct + " %";
                    })));
                    dialog.BeginInvoke((Action)(() => { dialog.Close(); Replace(owner, file, es); }));
                }
                catch (Exception ex)
                {
                    dialog.BeginInvoke((Action)(() =>
                    {
                        dialog.Close();
                        MessageBox.Show(owner, ex is CryptographicException
                            ? (es ? "El archivo descargado no es válido (dañado o no firmado por HPlay). No se ha cambiado nada." : "The downloaded file is not valid (damaged or not signed by HPlay). Nothing was changed.")
                            : (es ? "La descarga ha fallado. Se volverá a intentar la próxima vez que abras HPlay." : "The download failed. It will be retried next time you open HPlay."), "HPlay");
                    }));
                }
            });
            dialog.ShowDialog(owner);
        }

        static void Download(Manifest m, string file, Action<int, int> progress)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(file));
            if (File.Exists(file)) File.Delete(file);
            Exception last = null;
            for (int attempt = 1; attempt <= Attempts; attempt++)
            {
                if (attempt > 1) { progress(0, attempt); System.Threading.Thread.Sleep(2000 * (attempt - 1)); }
                try
                {
                    long have = File.Exists(file) ? new FileInfo(file).Length : 0;
                    if (have >= m.Size) break;
                    var req = (HttpWebRequest)WebRequest.Create(Base + Asset);
                    req.UserAgent = "HPlay-Receptor/" + InstalledName;
                    req.Timeout = 15000;
                    req.ReadWriteTimeout = 30000;
                    if (have > 0) req.AddRange(have);
                    using (var resp = (HttpWebResponse)req.GetResponse())
                    {
                        if (resp.StatusCode == HttpStatusCode.OK) have = 0; // sin reanudación: desde cero
                        using (var st = resp.GetResponseStream())
                        using (var outp = new FileStream(file, have > 0 ? FileMode.Append : FileMode.Create))
                        {
                            var buf = new byte[32 * 1024];
                            long done = have;
                            int n, lastPct = -1;
                            while ((n = st.Read(buf, 0, buf.Length)) > 0)
                            {
                                outp.Write(buf, 0, n);
                                done += n;
                                var pct = (int)Math.Min(99, done * 100 / m.Size);
                                if (pct != lastPct) { lastPct = pct; progress(pct, attempt); }
                            }
                        }
                    }
                    if (new FileInfo(file).Length != m.Size) throw new IOException("incomplete");
                    last = null;
                    break;
                }
                catch (Exception ex)
                {
                    last = ex;
                    if (File.Exists(file) && new FileInfo(file).Length > m.Size) File.Delete(file);
                }
            }
            if (!File.Exists(file) || new FileInfo(file).Length != m.Size) throw last ?? new IOException("incomplete");
            using (var sha = SHA256.Create())
            using (var st = File.OpenRead(file))
            {
                var hex = BitConverter.ToString(sha.ComputeHash(st)).Replace("-", "").ToLowerInvariant();
                if (hex != m.Sha256) { st.Close(); File.Delete(file); throw new CryptographicException("bad_hash"); }
            }
        }

        static void Replace(Form owner, string downloaded, bool es)
        {
            var exe = Application.ExecutablePath;
            try
            {
                if (File.Exists(exe + ".old")) File.Delete(exe + ".old");
                File.Move(exe, exe + ".old"); // un .exe en marcha se puede renombrar, no borrar
                try { File.Move(downloaded, exe); }
                catch { File.Move(exe + ".old", exe); throw; }
            }
            catch (Exception)
            {
                MessageBox.Show(owner, (es ? "No se ha podido sustituir el programa (¿carpeta protegida?). La versión nueva está en:\n" : "Could not replace the program (protected folder?). The new version is at:\n") + downloaded, "HPlay");
                Process.Start("explorer.exe", "/select,\"" + downloaded + "\"");
                return;
            }
            Process.Start(new ProcessStartInfo(exe, "--updated") { UseShellExecute = false });
            owner.Close();
        }
    }
}
