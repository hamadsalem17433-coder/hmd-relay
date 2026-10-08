package com.hmd.messenger

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.hmd.messenger.data.PeerInfo
import com.hmd.messenger.net.Codec
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class QrActivity : AppCompatActivity() {

    private val app get() = application as App

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedData(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14")) // Sleek deep slate
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val cardBg = GradientDrawable().apply {
            cornerRadius = 32f
            setColor(Color.parseColor("#151921"))
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            gravity = Gravity.CENTER
            setPadding(40, 48, 40, 48)
        }

        val titleTv = TextView(this).apply {
            text = "رمز الاقتران المشفر"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        cardLayout.addView(titleTv)

        val descTv = TextView(this).apply {
            text = "اعرض هذا الرمز للطرف الآخر أو قم بمسح رمزه لاقتران المفاتيح المشفرة مباشرة"
            setTextColor(Color.parseColor("#8696A0"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        cardLayout.addView(descTv)

        // ---------- إنشاء وإظهار رمز الـ QR الخاص بالمستخدم ----------
        val qrContainerBg = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(Color.WHITE)
        }

        val qrIv = ImageView(this).apply {
            background = qrContainerBg
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(480, 480).apply {
                bottomMargin = 40
            }
        }

        try {
            val keys = app.identity.getOrCreate()
            val myInfo = JSONObject().apply {
                put("server", "https://hmd-relay-server.onrender.com")
                put("dhPub", Codec.enc(keys.dhPublic))
                put("signPub", Codec.enc(keys.signPublic))
            }.toString()

            val barcodeEncoder = BarcodeEncoder()
            val bitmap = barcodeEncoder.encodeBitmap(myInfo, BarcodeFormat.QR_CODE, 440, 440)
            qrIv.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        cardLayout.addView(qrIv)

        // ---------- زر مسح رمز الطرف الآخر (Scan QR) ----------
        val btnBg = GradientDrawable().apply {
            cornerRadius = 48f
            setColor(Color.parseColor("#00A884")) // Emerald Accent
        }

        val scanBtn = Button(this).apply {
            text = "📷 مسح رمز الطرف الآخر"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            background = btnBg
            setPadding(40, 24, 40, 24)
            setOnClickListener {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("قم بتوجيه الكاميرا نحو رمز QR للطرف الآخر")
                    setCameraId(0)
                    setBeepEnabled(true)
                    setBarcodeImageEnabled(false)
                }
                qrLauncher.launch(options)
            }
        }
        cardLayout.addView(scanBtn)

        rootLayout.addView(cardLayout)

        setContentView(rootLayout)
    }

    private fun handleScannedData(data: String) {
        try {
            val json = JSONObject(data)
            val server = json.getString("server")
            val dhPub = json.getString("dhPub")
            val signPub = json.getString("signPub")

            val peer = PeerInfo(server, dhPub, signPub)
            app.db.dao().savePeer(peer)

            // إعادة تشغيل الاتصال بالمفاتيح الجديدة
            app.relay.stop()
            app.relay.start()

            Toast.makeText(this, "تم اقتران الطرف الآخر بنجاح! 🟢", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "رمز QR غير صالحة أو غير متوافق", Toast.LENGTH_SHORT).show()
        }
    }
}
