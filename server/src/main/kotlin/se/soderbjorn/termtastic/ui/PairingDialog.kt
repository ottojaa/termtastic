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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    val addresses = remember { LocalAddresses.ipv4() }
    val payloadUri = remember(token, fingerprint, addresses, port) {
        if (fingerprint == null || addresses.isEmpty()) null
        else PairingPayload(
            candidates = addresses.map { HostPort(it, port) },
            defaultPort = port,
            fingerprintHex = fingerprint,
            token = token,
            serverName = runCatching { InetAddress.getLocalHost().hostName }
                .getOrNull()?.takeIf { it.isNotBlank() },
        ).encode()
    }
    val qr = remember(payloadUri) { payloadUri?.let(::renderQrBitmap) }

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
                    if (qr == null) {
                        PairingUnavailable(fingerprint == null)
                    } else {
                        Text(
                            "Scan with the Termtastic app on your phone: " +
                                "Hosts → Scan pairing code.",
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.size(300.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = qr,
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
                        addresses.forEach { address ->
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
                            "Certificate SHA-256: ${prettyFingerprintShort(fingerprint!!)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.Start),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This code is like a password — anyone who scans it can " +
                                "connect. It expires in 5 minutes.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
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
 * Rasterize [content] as a QR code [ImageBitmap]. Error-correction level M
 * with a 1-module quiet zone keeps the module count low enough to scan at
 * 300 dp for payloads around [PairingPayload.MAX_LENGTH] chars. Black on
 * white regardless of theme — scanner contrast beats aesthetics here.
 *
 * @param content the encoded pairing URI.
 * @return the bitmap, or `null` if encoding failed (logged, never thrown).
 */
private fun renderQrBitmap(content: String): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 640, 640, hints)
    val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
        }
    }
    image.toComposeImageBitmap()
}.onFailure { log.warn("Failed to render pairing QR", it) }.getOrNull()
