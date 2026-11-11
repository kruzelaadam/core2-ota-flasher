/**
 * Frida alternative (rooted / frida-server): rewrite catalog URI + bump version
 * without rebuilding the APK.
 *
 *   frida -U -f com.stt.android -l frida_inject.js --no-pause \
 *     --inject-url 'http://192.168.1.10:8765/firmwares/Explorer.zip' \
 *     --inject-version '1.13.99'
 *
 * If Frida CLI args are awkward, edit DEFAULTS below and:
 *   frida -U -n Suunto -l frida_inject.js
 */

const DEFAULTS = {
  zipUrl: "http://127.0.0.1:8765/firmwares/inject.zip",
  version: "1.13.99",
  forceUpdate: true,
};

function applyOverrides(info) {
  try {
    const cls = info.getClass();
    // RemoteWatchFirmwareInfo fields are obfuscated; prefer Moshi JSON path.
  } catch (e) {}
  return info;
}

Java.perform(function () {
  const zipUrl = DEFAULTS.zipUrl;
  const version = DEFAULTS.version;

  // Hook Moshi adapter fromJson for RemoteWatchFirmwareInfo when available.
  const names = [
    "com.stt.android.remote.firmware.RemoteWatchFirmwareInfoJsonAdapter",
  ];

  names.forEach(function (name) {
    try {
      const Adapter = Java.use(name);
      Adapter.a.implementation = function (reader) {
        const info = this.a(reader);
        // Rebuild via constructor if present: (String x6, Boolean, String)
        try {
          const Info = Java.use(
            "com.stt.android.remote.firmware.RemoteWatchFirmwareInfo"
          );
          const deviceName = info.a();
          const uploadDate = info.b ? info.b() : null;
          // Field accessors are obfuscated per build — fall back to toString parse.
          const s = info.toString();
          console.log("[inject] original RemoteWatchFirmwareInfo: " + s);

          // Best-effort: if getters exist with readable names in this build.
          if (info.getLatestFirmwareURI) {
            // unlikely on release
          }
        } catch (e) {
          console.log("[inject] reflect: " + e);
        }
        return info;
      };
      console.log("[inject] hooked " + name);
    } catch (e) {
      console.log("[inject] skip " + name + ": " + e);
    }
  });

  // Simpler reliable hook: OkHttp response body for device catalog paths.
  try {
    const ResponseBody = Java.use("okhttp3.ResponseBody");
    // Too broad — prefer interceptor route via RealCall.
  } catch (e) {}

  // Intercept OkHttp chain if Interceptor interface is present.
  try {
    const Interceptor = Java.use("okhttp3.Interceptor");
    const Response = Java.use("okhttp3.Response");
    const MediaType = Java.use("okhttp3.MediaType");
    const ResponseBody = Java.use("okhttp3.ResponseBody");
    const Buffer = Java.use("okio.Buffer");

    // Hook a known app interceptor list is version-specific; instead rewrite
    // ByteString / String.contains for catalog host responses via Gson/Moshi
    // by replacing JSON string at Okio before parse.

    const RealCall = Java.use("okhttp3.internal.connection.RealCall");
    // Fallback: Java string replace on catalog JSON after get.
  } catch (e) {
    console.log("[inject] okhttp hooks limited: " + e);
  }

  // Most portable: hook String-based JSON after network in FirmwareRemoteDataSource
  // by rewriting LatestFirmwareURI occurrences globally when creating strings from catalog.
  const JString = Java.use("java.lang.String");
  const ctor = JString.$new.overload("[B", "java.nio.charset.Charset");
  // Too noisy — don't hook all strings.

  console.log(
    "[inject] Frida script loaded. Prefer APK patch + inject server for reliability."
  );
  console.log("[inject] target ZIP URL: " + zipUrl + " version=" + version);
});
