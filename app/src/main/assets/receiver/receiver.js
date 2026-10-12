/*
 * Receptor HPlay. ES5 a propósito: navegadores de tele de 2016+ (webOS 3, Tizen 2.4, Opera TV…).
 * Orden de preferencia para el espejo:
 *   1. MSE + jmuxer (H.264 Baseline + AAC por WebSocket)  → <1 s
 *   2. MSE sin audio si el AAC falla                       → <1 s, sin sonido
 *   3. HLS nativo del navegador (/live.m3u8)               → 3–6 s
 * Para vídeo por URL: <video src> nativo, o hls.js si no hay HLS nativo pero sí MSE.
 */
(function () {
  'use strict';
  var es = (navigator.language || 'en').toLowerCase().indexOf('es') === 0;
  var L = es ? {
    connecting: 'Conectando con el móvil…', waiting: 'Esperando a que el móvil emita…', reconnect: 'Conexión perdida, reintentando…',
    sound: 'Activar sonido', muted: 'Pulsa OK o «Activar sonido» para oír', full: 'Pantalla completa', noaudio: 'Sin audio en este navegador',
    hls: 'Modo compatible (HLS, más retardo)', nomse: 'Este navegador no puede reproducir el directo. Usa DLNA o la app HPlay.', ended: 'Emisión terminada'
  } : {
    connecting: 'Connecting to the phone…', waiting: 'Waiting for the phone to cast…', reconnect: 'Connection lost, retrying…',
    sound: 'Enable sound', muted: 'Press OK or «Enable sound» to hear', full: 'Full screen', noaudio: 'No audio in this browser',
    hls: 'Compatible mode (HLS, more delay)', nomse: 'This browser cannot play the live stream. Use DLNA or the HPlay app.', ended: 'Cast ended'
  };

  var v = document.getElementById('v');
  var ov = document.getElementById('ov');
  var msg = document.getElementById('msg');
  var snd = document.getElementById('snd');
  var fs = document.getElementById('fs');
  snd.textContent = L.sound;
  fs.textContent = L.full;

  var base = location.pathname.replace(/[^\/]*$/, '');
  var MS = window.MediaSource || window.WebKitMediaSource;
  function supported(type) { try { return !!MS && MS.isTypeSupported(type); } catch (e) { return false; } }
  var caps = {
    mse: !!MS,
    base: supported('video/mp4; codecs="avc1.42E01E"'),
    high: supported('video/mp4; codecs="avc1.640028"'),
    aac: supported('audio/mp4; codecs="mp4a.40.2"'),
    hls: !!(v.canPlayType && v.canPlayType('application/vnd.apple.mpegurl'))
  };

  var ws = null, jm = null, hls = null, state = null, retry = 1000, lastData = 0, audioBroken = false, withAudio = false, hideTimer = null;

  function show(text, keep) {
    msg.textContent = text;
    ov.className = '';
    clearTimeout(hideTimer);
    if (!keep) hideTimer = setTimeout(function () { ov.className = 'hide'; }, 5000);
  }

  function teardown() {
    if (jm) { try { jm.destroy(); } catch (e) {} jm = null; }
    if (hls) { try { hls.destroy(); } catch (e) {} hls = null; }
    v.removeAttribute('src');
    try { v.load(); } catch (e) {}
  }

  function tryPlay() {
    var p = v.play();
    if (p && p.catch) p.catch(function () { v.muted = true; v.play(); show(L.muted, true); });
  }

  function startMirror(hasAudio) {
    teardown();
    if (!caps.mse || !caps.base) {
      if (caps.hls) { v.src = base + 'live.m3u8'; tryPlay(); show(L.hls); } else show(L.nomse, true);
      return;
    }
    withAudio = hasAudio && caps.aac && !audioBroken;
    jm = new JMuxer({
      node: v,
      mode: withAudio ? 'both' : 'video',
      flushingTime: 0,      // cada trama llega completa: se mete en MSE en cuanto llega
      maxDelay: 500,        // jmuxer salta adelante si se acumula más de medio segundo
      clearBuffer: true,
      fps: 30,
      live: true,
      debug: false,
      onError: function () {
        // Primer fallo con audio: se reintenta solo vídeo (navegadores con AAC-MSE roto).
        if (withAudio) { audioBroken = true; show(L.noaudio); startMirror(false); }
      }
    });
    // Primero con sonido (apps de tele y navegadores que lo permiten); si el navegador lo bloquea, tryPlay silencia.
    v.muted = false;
    tryPlay();
    show('');
  }

  function startUrl(src, kind) {
    teardown();
    // hls.js antes que el HLS «nativo»: algunos Chromium dicen «maybe» y luego fallan.
    if (kind === 'hls' && caps.mse) {
      loadScript(base + 'hls.light.min.js', function () {
        if (!window.Hls || !window.Hls.isSupported()) { v.src = src; tryPlay(); return; }
        hls = new window.Hls();
        hls.on(window.Hls.Events.ERROR, function (ev, data) {
          if (data && data.fatal && caps.hls) { hls.destroy(); hls = null; v.src = src; tryPlay(); }
        });
        hls.loadSource(src);
        hls.attachMedia(v);
        tryPlay();
      });
    } else {
      v.src = src;
      tryPlay();
    }
    v.muted = false;
  }

  function loadScript(url, cb) {
    if (window.Hls) return cb();
    var s = document.createElement('script');
    s.src = url;
    s.onload = cb;
    s.onerror = cb;
    document.body.appendChild(s);
  }

  function onText(o) {
    if (o.type === 'mirror') { state = o; startMirror(!!o.audio); }
    else if (o.type === 'url') { state = o; startUrl(o.src, o.kind); }
    else if (o.type === 'idle') { state = o; teardown(); show(L.waiting, true); }
    else if (o.type === 'cmd') {
      if (o.cmd === 'play') tryPlay();
      else if (o.cmd === 'pause') v.pause();
      else if (o.cmd === 'stop') { teardown(); show(L.ended, true); }
      else if (o.cmd === 'seek') { try { v.currentTime = o.value; } catch (e) {} }
      else if (o.cmd === 'volume') { v.volume = Math.max(0, Math.min(1, o.value / 100)); }
    }
  }

  function onBinary(buf) {
    if (!jm) return;
    var d = new Uint8Array(buf);
    var dur = ((d[1] << 24) | (d[2] << 16) | (d[3] << 8) | d[4]) >>> 0;
    var payload = d.subarray(5);
    if (d[0] === 1) jm.feed({ video: payload, duration: dur, isLastVideoFrameComplete: true });
    else if (d[0] === 2 && withAudio) jm.feed({ audio: payload, duration: dur });
  }

  function connect() {
    show(L.connecting, true);
    var url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + base + 'ws';
    try { ws = new WebSocket(url); } catch (e) { return retryLater(); }
    ws.binaryType = 'arraybuffer';
    ws.onopen = function () {
      retry = 1000;
      lastData = Date.now();
      ws.send(JSON.stringify({ type: 'hello', ua: navigator.userAgent, caps: caps, w: screen.width, h: screen.height }));
    };
    ws.onmessage = function (e) {
      lastData = Date.now();
      if (typeof e.data === 'string') { try { onText(JSON.parse(e.data)); } catch (x) {} }
      else onBinary(e.data);
    };
    ws.onclose = function () { ws = null; retryLater(); };
    ws.onerror = function () { try { ws.close(); } catch (e) {} };
  }

  function retryLater() {
    show(L.reconnect, true);
    setTimeout(connect, retry);
    retry = Math.min(retry * 2, 5000);
  }

  // Vigilancia: latencia en el espejo, conexión muerta y estado para el mando del móvil.
  setInterval(function () {
    if (ws && Date.now() - lastData > 12000) { try { ws.close(); } catch (e) {} }
    if (state && state.type === 'mirror' && jm && v.buffered.length) {
      var end = v.buffered.end(v.buffered.length - 1);
      if (end - v.currentTime > 0.8) v.currentTime = end - 0.1;
      if (v.paused) tryPlay();
    }
    if (ws && ws.readyState === 1 && state && state.type === 'url') {
      ws.send(JSON.stringify({ type: 'status', pos: v.currentTime || 0, dur: isFinite(v.duration) ? v.duration : 0, paused: v.paused, vol: v.volume }));
    }
  }, 1000);

  function unmute() { v.muted = false; tryPlay(); show(''); }
  function fullscreen() {
    var el = document.documentElement;
    var f = el.requestFullscreen || el.webkitRequestFullscreen || el.mozRequestFullScreen || el.msRequestFullscreen;
    if (f) { try { f.call(el); } catch (e) {} }
  }
  snd.onclick = unmute;
  fs.onclick = fullscreen;
  document.addEventListener('keydown', function (e) {
    // OK/Enter del mando: sonido + pantalla completa (los navegadores exigen un gesto del usuario).
    if (e.keyCode === 13 && document.activeElement !== snd && document.activeElement !== fs) { unmute(); fullscreen(); }
    else show(msg.textContent);
  });
  document.addEventListener('mousemove', function () { show(msg.textContent); });

  connect();
})();
