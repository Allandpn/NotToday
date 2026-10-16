function FindProxyForURL(url, host) {
  var m = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (!m && host.indexOf(":") < 0) return "DIRECT";
  if (m) {
    var a = +m[1], b = +m[2];
    if (a === 10 || a === 127 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127)) return "DIRECT";
  } else {
    var h = host.replace(/^\[|\]$/g, "").toLowerCase();
    if (h === "::1" || /^f[cd]/.test(h) || /^fe[89ab]/.test(h)) return "DIRECT";
  }
  return "PROXY 127.0.0.1:9";
}
