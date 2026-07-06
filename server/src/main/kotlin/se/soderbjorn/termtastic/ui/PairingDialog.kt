/**
 * The "Pair a device" dialog: renders a QR code that a phone scans to
 * connect with no typing, no TOFU gamble, and no approval dialog.
 *
 * The QR carries a [PairingPayload]: every candidate address the server is
 * reachable at, the TLS leaf fingerprint (so the phone pins from the very
 * first connect), and a one-time pairing token minted when the dialog opens
 * and killed when it closes ([PairingTokens]).
 *
 * Opened from the settings dialog's Connections section.
 *
 * @see SettingsDialog
 * @see PairingTokens
 * @see PairingPayload
 */
package se.soderbjorn.termtastic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.HostPort
import se.soderbjorn.termtastic.PairingPayload
import se.soderbjorn.termtastic.auth.DeviceAuth
import se.soderbjorn.termtastic.auth.PairingTokens
import se.soderbjorn.termtastic.net.LocalAddresses
import java.awt.image.BufferedImage
import java.net.InetAddress

private val log = LoggerFactory.getLogger("se.soderbjorn.termtastic.ui.PairingDialog")

/**
 * Render the pairing dialog window. Composed only while the dialog is open,
 * so the `remember`-minted pairing token's lifetime exactly matches the QR's
 * time on screen: fresh token per open, invalidated on close, hard 5-minute
 * TTL in between.
 *
 * Called from [SettingsDialog]'s Connections section while its "Pair a
 * device" state flag is set.
 *
 * @param port the TCP port the server is listening on, embedded in the
 *   payload as the default port for all candidates.
 * @param onClose invoked when the user dismisses the dialog; the caller
 *   clears its flag and bumps the settings refresh counter (a pairing may
 *   have flipped allow-remote on while the dialog was up).
 */
@Composable
internal fun PairingDialog(port: Int, onClose: () -> Unit) {
    val token = remember { PairingTokens.mint() }
    DisposableEffect(Unit) {
        onDispose { PairingTokens.invalidate(token) }
    }

    val fingerprint = DeviceAuth.serverCertFingerprintHex
    // Build addresses, payload, and QR off the Compose UI thread: a
    // reverse-DNS getLocalHost() and the QR raster can each take seconds on a
    // slow resolver, and doing them in composition would freeze the whole
    // desktop app while the dialog opens. `null` = still computing → spinner.
    val data by produceState<PairingData?>(initialValue = null, token, fingerprint, port) {
        value = withContext(Dispatchers.IO) { buildPairingData(token, fingerprint, port) }
    }

    val dialogState = rememberDialogState(
        size = DpSize(420.dp, 560.dp),
        position = WindowPosition.Aligned(Alignment.Center),
    )
    DialogWindow(
        onCloseRequest = onClose,
        title = "Termtastic — Pair a device",
        state = dialogState,
        alwaysOnTop = true,
        resizable = false,
    ) {
        MaterialTheme(colorScheme = SettingsDialog.tronColorScheme) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val current = data
                    when {
                        fingerprint == null -> PairingUnavailable(missingFingerprint = true)
                        current == null -> CircularProgressIndicator()
                        current.qr == null -> PairingUnavailable(missingFingerprint = false)
                        else -> PairingContent(current, fingerprint, port)
                    }
                }
            }
        }
    }
}

/**
 * Everything the dialog derives from off-thread work: the QR bitmap and the
 * address list to display. `qr` is null when no payload could be built (no
 * reachable address), which the dialog renders as [PairingUnavailable].
 */
private class PairingData(
    val addresses: List<String>,
    val qr: ImageBitmap?,
)

/**
 * Do the blocking pairing setup — NIC enumeration, hostname reverse-DNS, and
 * the QR raster — off the UI thread. Called from [PairingDialog]'s
 * `produceState` on [Dispatchers.IO].
 *
 * @param token the minted pairing token to embed.
 * @param fingerprint the server's TLS leaf fingerprint, or null if unknown.
 * @param port the default port for the candidate endpoints.
 * @return the address list plus a QR bitmap, or a null bitmap when no payload
 *   could be built (missing fingerprint or no reachable address).
 */
private fun buildPairingData(token: String, fingerprint: String?, port: Int): PairingData {
    val addresses = LocalAddresses.ipv4()
    if (fingerprint == null || addresses.isEmpty()) return PairingData(addresses, null)
    val uri = PairingPayload(
        candidates = addresses.map { HostPort(it, port) },
        defaultPort = port,
        fingerprintHex = fingerprint,
        token = token,
        serverName = runCatching { InetAddress.getLocalHost().hostName }
            .getOrNull()?.takeIf { it.isNotBlank() },
    ).encode()
    return PairingData(addresses, renderQrBitmap(uri))
}

/**
 * The populated pairing body: QR, the "On this Wi-Fi" address list, the cert
 * fingerprint, and the password-like warning.
 *
 * @param data the off-thread-built addresses + QR bitmap ([PairingData.qr]
 *   is guaranteed non-null here).
 * @param fingerprint the server's TLS leaf fingerprint (non-null here).
 * @param port the default port shown alongside each address.
 */
@Composable
private fun ColumnScope.PairingContent(data: PairingData, fingerprint: String, port: Int) {
    Text(
        "Scan with the Termtastic app on your phone: Hosts → Scan pairing code.",
        fontSize = 13.sp,
    )
    Spacer(Modifier.height(12.dp))
    Box(
        modifier = Modifier.size(300.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = data.qr!!,
            contentDescription = "Pairing QR code",
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize(),
        )
    }
    Spacer(Modifier.height(12.dp))

    Text(
        "On this Wi-Fi",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.Start),
    )
    Spacer(Modifier.height(4.dp))
    data.addresses.forEach { address ->
        Text(
            "$address:$port",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.Start),
        )
    }
    Text(
        "Your phone must be on the same Wi-Fi network as this computer.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.align(Alignment.Start),
    )
    Spacer(Modifier.height(12.dp))

    Text(
        "Certificate SHA-256: ${prettyFingerprintShort(fingerprint)}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier.align(Alignment.Start),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "This code is like a password — anyone who scans it can connect. " +
            "It expires in 5 minutes.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth(),
    )
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
 * ~410k). The dialog upscales it with [FilterQuality.None], so nearest-
 * neighbour keeps the modules crisp. Error-correction level M with a 1-module
 * quiet zone stays scannable at 300 dp for payloads near
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
