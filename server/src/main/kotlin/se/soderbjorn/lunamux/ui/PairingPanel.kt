/**
 * The "Pair a device" panel: renders a QR code that a phone scans to
 * connect with no typing, no TOFU gamble, and no approval dialog.
 *
 * The QR carries a [PairingPayload]: every candidate address the server is
 * reachable at, the TLS leaf fingerprint (so the phone pins from the very
 * first connect), and a one-time pairing token minted when the panel opens
 * and killed when it closes ([PairingTokens]).
 *
 * Rendered *inside* the settings window in place of its normal content, not
 * as a window of its own — a QR is something you hold a phone up to, and a
 * second window to lose behind the first helped nobody. [PairingPanel] fills
 * the settings window and offers a back button; the settings window's own
 * chrome is the only chrome.
 *
 * @see SettingsDialog
 * @see PairingTokens
 * @see PairingPayload
 */
package se.soderbjorn.lunamux.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.HostPort
import se.soderbjorn.lunamux.PairingPayload
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.auth.PairingTokens
import se.soderbjorn.lunamux.net.LocalAddresses
import se.soderbjorn.lunamux.net.LocalEndpoint
import java.awt.image.BufferedImage
import java.net.InetAddress

private val log = LoggerFactory.getLogger("se.soderbjorn.lunamux.ui.PairingPanel")

/**
 * Height cap on the expanded address list before it scrolls internally.
 *
 * Sized so a typical machine's addresses fit without scrolling at all, while a
 * docked laptop on a VPN with containers running — which can offer eight or
 * more — scrolls rather than pushing the certificate fingerprint out of the
 * 780dp settings window.
 */
private val ADDRESS_LIST_MAX_HEIGHT = 190.dp

/**
 * The pairing screen, filling the settings window.
 *
 * Composed only while pairing is on screen, so the `remember`-minted pairing
 * token's lifetime exactly matches the QR's time in view: fresh token per
 * open, invalidated on back/close, hard 5-minute TTL in between.
 *
 * Called from [SettingsDialog]'s content while its "Pair via QR code" state
 * flag is set — only reachable with allow-remote on, since the button that
 * sets that flag is not rendered otherwise.
 *
 * @param port the TCP port the server is listening on, embedded in the
 *   payload as the default port for all candidates.
 * @param onBack invoked when the user leaves pairing; the caller clears its
 *   flag and bumps the settings refresh counter (a pairing may have added a
 *   trusted device while the QR was up).
 */
@Composable
internal fun PairingPanel(port: Int, onBack: () -> Unit) {
    val token = remember { PairingTokens.mint() }
    DisposableEffect(Unit) {
        onDispose { PairingTokens.invalidate(token) }
    }

    val fingerprint = DeviceAuth.serverCertFingerprintHex

    // Discovery runs off the UI thread: NIC enumeration plus a reverse-DNS
    // lookup per address, which blocks with no cancellable JDK variant. `null`
    // = still discovering → spinner.
    val endpoints by produceState<List<LocalEndpoint>?>(initialValue = null) {
        value = LocalAddresses.endpoints()
    }

    val qr by produceState<ImageBitmap?>(null, endpoints, token, fingerprint, port) {
        val found = endpoints
        value = if (found == null || fingerprint == null) {
            null
        } else {
            withContext(Dispatchers.IO) { buildQr(found, token, fingerprint, port) }
        }
    }

    // No panel-wide scroll: with the address list collapsed everything fits,
    // and the one thing that can outgrow the window scrolls inside itself.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Settings") }
            Spacer(Modifier.width(8.dp))
            Text("Pair a device", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))

        val found = endpoints
        when {
            fingerprint == null -> PairingUnavailable(missingFingerprint = true)
            found == null -> CircularProgressIndicator()
            found.isEmpty() -> PairingUnavailable(missingFingerprint = false)
            else -> PairingContent(
                endpoints = found,
                qr = qr,
                fingerprint = fingerprint,
                port = port,
            )
        }
    }
}

/**
 * The populated pairing body: QR, the list of what it carries, the cert
 * fingerprint, and the password-like warning.
 *
 * The address list is informational, not a choice. Every endpoint travels in
 * the QR and the phone keeps all of them, walking them in order and promoting
 * whichever answers — so picking a "preferred" one here would only reorder a
 * list that reorders itself on first connect. It is shown because "which
 * addresses does my phone actually have" is the question you need answered
 * when pairing doesn't work.
 *
 * @param endpoints every address and name this machine offers, in the order
 *   the QR carries them.
 * @param qr the QR bitmap, or null while it rasters.
 * @param fingerprint the server's TLS leaf fingerprint (non-null here).
 * @param port the default port shown alongside each address.
 */
@Composable
private fun ColumnScope.PairingContent(
    endpoints: List<LocalEndpoint>,
    qr: ImageBitmap?,
    fingerprint: String,
    port: Int,
) {
    Text(
        "Scan with the Lunamux app on your phone: Hosts → Scan QR code.",
        fontSize = 13.sp,
    )
    Spacer(Modifier.height(12.dp))
    Box(modifier = Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        if (qr == null) {
            CircularProgressIndicator()
        } else {
            Image(
                bitmap = qr,
                contentDescription = "Pairing QR code",
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    // Directly under the QR, where someone about to hold their phone up to it
    // will actually read it. This code grants a device access to every
    // terminal on this machine, so it gets the error palette rather than the
    // muted caption grey the rest of the panel's asides use.
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text("⚠", fontSize = 15.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                "This code is like a password — anyone who scans it can connect. " +
                    "It expires in 5 minutes.",
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    // Collapsed by default: the QR and the warning are what pairing is about,
    // and the address list only matters when it isn't working. Expanded it is
    // long enough to push the fingerprint off-screen, so it scrolls in its own
    // bounded box rather than making the whole panel scroll — that way the QR
    // never moves out from under a phone that is being held up to it.
    var showAddresses by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showAddresses = !showAddresses },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (showAddresses) "▾" else "▸",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "This code carries ${endpoints.size} " +
                if (endpoints.size == 1) "address" else "addresses",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (showAddresses) {
        Text(
            "Your phone keeps all of these and uses whichever one answers.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.Start),
        )
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = ADDRESS_LIST_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            endpoints.forEach { endpoint ->
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(
                        "${endpoint.host}:$port",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                    Text(
                        endpointCaption(endpoint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    Text(
        "Certificate SHA-256: ${prettyFingerprintShort(fingerprint)}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier.align(Alignment.Start),
    )
}

/**
 * The provenance caption for an endpoint — see [EndpointRow] for why this is
 * spelled out rather than left to the user to infer.
 *
 * @param endpoint the endpoint to describe.
 * @return a short human-readable origin.
 */
private fun endpointCaption(endpoint: LocalEndpoint): String = when (endpoint.kind) {
    LocalEndpoint.Kind.IP ->
        "${endpoint.nic ?: "this machine"} · may change when the network reassigns it"
    LocalEndpoint.Kind.RESOLVED_NAME ->
        "${endpoint.nic ?: "name"} · confirmed by DNS · survives address changes"
    LocalEndpoint.Kind.SELF_NAME ->
        "mDNS name · survives address changes · iPhone only"
}

/**
 * Build the pairing URI and rasterize it.
 *
 * [endpoints] arrives ranked (see `LocalAddresses`), and that order is what
 * the QR carries — it decides two things. The phone walks candidates
 * *sequentially* with a 12s timeout each (`CandidateConnector`), so position
 * is worth up to 12s of spinner; and `PairingPayload.encode` trims from the
 * tail, so position also decides what survives when the payload is full.
 * Both reasons point the same way: most-reachable first.
 *
 * @param endpoints every discovered endpoint, most-reachable first.
 * @param token the minted pairing token to embed.
 * @param fingerprint the server's TLS leaf fingerprint.
 * @param port the default port for the candidate endpoints.
 * @return the QR bitmap, or null when encoding failed.
 */
private fun buildQr(
    endpoints: List<LocalEndpoint>,
    token: String,
    fingerprint: String,
    port: Int,
): ImageBitmap? {
    val uri = PairingPayload(
        candidates = endpoints.map { HostPort(it.host, port) },
        defaultPort = port,
        fingerprintHex = fingerprint,
        token = token,
        serverName = runCatching { InetAddress.getLocalHost().hostName }
            .getOrNull()?.takeIf { it.isNotBlank() },
    ).encode()
    // encode() silently sheds candidates that don't fit the QR; say so, or a
    // "my phone won't reach the Mac on the VPN address" report has nothing to
    // go on. Ranked order means the survivors are the ones that matter.
    val emitted = uri.substringAfter("&h=").substringBefore("&").split(',').size
    if (emitted < endpoints.size) {
        log.info(
            "PairingPanel: QR carries {} of {} endpoints (dropped {} to stay scannable)",
            emitted, endpoints.size, endpoints.size - emitted,
        )
    }
    return renderQrBitmap(uri)
}

/**
 * Fallback body when a payload can't be built: no TLS fingerprint (headless
 * boot) or no non-loopback address (machine is offline / on loopback only).
 *
 * @param missingFingerprint `true` when the cert fingerprint is the missing
 *   piece; `false` means no usable network address was found.
 */
@Composable
private fun PairingUnavailable(missingFingerprint: Boolean) {
    Text(
        if (missingFingerprint) {
            "Pairing is unavailable: the server's TLS certificate fingerprint " +
                "isn't known in this session."
        } else {
            "Pairing is unavailable: this computer has no network address " +
                "other devices could reach. Join a Wi-Fi network and reopen " +
                "this dialog."
        },
        fontSize = 13.sp,
    )
}

/** Shortened `AB:CD:…:EF` form of a 64-char hex fingerprint for captions. */
private fun prettyFingerprintShort(hex: String): String {
    val pairs = hex.uppercase().chunked(2)
    return (pairs.take(4) + "…" + pairs.takeLast(4)).joinToString(":")
}

/**
 * Rasterize [content] as a QR code [ImageBitmap] at its natural module
 * resolution (requesting a 1×1 size makes zxing clamp up to exactly the
 * module count + quiet zone — a few thousand pixels, not the former 640×640 /
 * ~410k). The panel upscales it with [FilterQuality.None], so nearest-
 * neighbour keeps the modules crisp. Error-correction level M with a 1-module
 * quiet zone stays scannable at 260 dp for payloads near
 * [PairingPayload.MAX_LENGTH]. Black on white regardless of theme — scanner
 * contrast beats aesthetics.
 *
 * @param content the encoded pairing URI.
 * @return the bitmap, or `null` if encoding failed (logged, never thrown).
 */
private fun renderQrBitmap(content: String): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, hints)
    val w = matrix.width
    val h = matrix.height
    // Fill an int buffer and blit it in one setRGB call rather than w*h calls.
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            pixels[row + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, w, h, pixels, 0, w)
    image.toComposeImageBitmap()
}.onFailure { log.warn("Failed to render pairing QR", it) }.getOrNull()
