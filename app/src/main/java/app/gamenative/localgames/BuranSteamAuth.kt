package app.gamenative.localgames

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.WindowManager
import android.widget.ImageView
import app.gamenative.Crypto
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.utils.BrightnessManager
import app.gamenative.utils.SteamUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthTokenPlatformType
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.authentication.QrAuthSession
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Authorizes a separate Steam client without changing Gameplay's active Steam connection. */
internal object BuranSteamAuth {
    class Authorization(val generation: UUID, val refreshToken: String)
    class Cancelled : Exception()
    private class AuthFailure(message: String) : IllegalStateException(message)

    suspend fun authorize(context: Context, accountId: Long, accountName: String): Authorization {
        val preferences = store(context)
        preferences.getString(accountId.toString(), null)?.let { value ->
            val decoded = Crypto.decryptFromString(value)?.decodeToString()
            val generation = decoded?.substringBefore('\n')?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            }
            val token = decoded?.substringAfter('\n', "")
            if (generation != null && !token.isNullOrBlank()) return Authorization(generation, token)
        }

        val token = try {
            withTimeoutOrNull(180_000) { approve(context, accountId, accountName) }
                ?: throw AuthFailure(context.getString(R.string.buran_steam_auth_expired))
        } catch (error: Exception) {
            if (error is Cancelled || error is CancellationException || error is AuthFailure) throw error
            throw AuthFailure(context.getString(R.string.buran_steam_auth_failed))
        }
        val result = Authorization(UUID.randomUUID(), token)
        val encrypted = Crypto.encryptToString("${result.generation}\n${result.refreshToken}".encodeToByteArray())
        check(preferences.edit().putString(accountId.toString(), encrypted).commit()) {
            context.getString(R.string.buran_steam_auth_failed)
        }
        return result
    }

    fun forgetRejected(context: Context, accountId: Long) {
        store(context).edit().remove(accountId.toString()).apply()
    }

    private fun store(context: Context) =
        context.getSharedPreferences("buran_steam_client_auth", Context.MODE_PRIVATE)

    private suspend fun approve(context: Context, accountId: Long, accountName: String): String = coroutineScope {
        val activity = BrightnessManager.findActivity(context)
            ?: error(context.getString(R.string.buran_steam_auth_failed))
        val client = SteamService.instance?.steamClient
            ?: error(context.getString(R.string.buran_steam_client_sign_in_required))
        val details = AuthSessionDetails().apply {
            deviceFriendlyName = "Buran (${SteamUtils.getMachineName(context)})"
            clientOSType = EOSType.WinUnknown
            platformType = EAuthTokenPlatformType.k_EAuthTokenPlatformType_SteamClient
            persistentSession = true
        }
        val session = client.authentication.beginAuthSessionViaQR(details).await()
        val dismissed = CompletableDeferred<Unit>()
        var dialog: AlertDialog? = null
        try {
            withContext(Dispatchers.Main) {
                val image = ImageView(activity).apply {
                    setImageBitmap(qrBitmap(session.challengeUrl))
                    adjustViewBounds = true
                    maxHeight = (250 * resources.displayMetrics.density).toInt()
                    contentDescription = context.getString(R.string.buran_steam_auth_title)
                }
                val shown = AlertDialog.Builder(activity)
                    .setTitle(R.string.buran_steam_auth_title)
                    .setMessage(R.string.buran_steam_auth_instructions)
                    .setView(image)
                    .setNegativeButton(R.string.cancel) { _, _ -> dismissed.complete(Unit) }
                    .create()
                dialog = shown
                shown.setOnDismissListener { dismissed.complete(Unit) }
                shown.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                shown.show()
                session.challengeUrlChanged = object : IChallengeUrlChanged {
                    override fun onChanged(qr: QrAuthSession?) {
                        if (qr == null) return
                        activity.runOnUiThread {
                            if (shown.isShowing) {
                                runCatching { qrBitmap(qr.challengeUrl) }.getOrNull()?.let(image::setImageBitmap)
                            }
                        }
                    }
                }
            }
            val polling = async { session.pollingWaitForResult().await() }
            val approved = try {
                select {
                    polling.onAwait { it }
                    dismissed.onAwait { throw Cancelled() }
                }
            } finally {
                polling.cancel()
            }
            if (!approved.accountName.equals(accountName, ignoreCase = true) ||
                SteamService.userSteamId?.convertToUInt64() != accountId
            ) throw AuthFailure(context.getString(R.string.buran_steam_auth_wrong_account))
            approved.refreshToken.takeIf { it.isNotBlank() }
                ?: error(context.getString(R.string.buran_steam_auth_failed))
        } finally {
            session.challengeUrlChanged = null
            withContext(NonCancellable + Dispatchers.Main) {
                dialog?.dismiss()
            }
        }
    }

    private fun qrBitmap(challenge: String): Bitmap {
        val matrix = QRCodeWriter().encode(challenge, BarcodeFormat.QR_CODE, 336, 336)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}
