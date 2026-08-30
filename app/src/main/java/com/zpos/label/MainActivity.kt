package com.zpos.label

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zpos.label.api.Produk
import com.zpos.label.api.ZposApi
import com.zpos.label.api.barcodeLabel
import com.zpos.label.bt.BluetoothPrinter
import com.zpos.label.databinding.ActivityMainBinding
import com.zpos.label.databinding.ItemProdukBinding
import com.zpos.label.escpos.EscPosLabel
import com.zpos.label.update.Updater
import com.zpos.label.util.CrashReport
import com.zpos.label.util.Logger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        // exception coroutine yang tak di-catch dulu bocor -> app keluar sendiri tanpa jejak.
        // Log lalu biarkan app TETAP JALAN (jangan crash mati).
        if (::prefs.isInitialized) CrashReport.catat(applicationContext, e, "COROUTINE")
    })

    private var semua: MutableList<Produk> = mutableListOf()
    private var tampil: MutableList<Produk> = mutableListOf()
    private val selected = HashSet<Long>()
    private lateinit var adapter: ProdukAdapter

    private var printerAddress: String? = null
    private var scanAktif = false
    private var printerDialog: AlertDialog? = null
    private var sortMode = "baru"          // "baru" (id turun) | "nama" (A-Z)
    private var paperW = 25                 // mm, default 25x15
    private var paperH = 15
    private var proto = "tspl"              // "tspl" (printer label clabel/... ) | "esc"
    private var fontMul = 1                  // 1|2|3 => S/M/L utk TEXT nama & harga label (seragam)
    private var barcodeMode = "1d"           // "1d" (EAN-13/Code128) | "2d" (QR)
    private var bcSrc = "6"                  // "6" (barcode_internal label) | "13" (barcode asli)

    private val permReq =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // cukup coba start; sesudah grant user pilih via bluetooth isEnabled
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(applicationContext)   // tangkap crash sebelum app keluar
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = getSharedPreferences("z1label", Context.MODE_PRIVATE)
        printerAddress = prefs.getString("printer", null)
        sortMode = prefs.getString("sort", "baru") ?: "baru"
        proto = prefs.getString("proto", "tspl") ?: "tspl"
        fontMul = prefs.getInt("fontMul", 1)
        barcodeMode = prefs.getString("barcodeMode", "1d") ?: "1d"
        bcSrc = prefs.getString("bcSrc", "6") ?: "6"
        b.btnFont.text = "Font: " + fontLabel(fontMul)
        b.btnBarcode.text = barcodeBtnLabel()
        b.btnBcSrc.text = bcSrcBtnLabel()
        val paper = (prefs.getString("paper", "25x15") ?: "25x15").split("x")
        paperW = paper.getOrNull(0)?.toIntOrNull() ?: 25
        paperH = paper.getOrNull(1)?.toIntOrNull() ?: 15
        b.btnSort.text = if (sortMode == "baru") "Urut: Terbaru" else "Urut: Nama A-Z"
        b.btnPaper.text = "Kertas ${paperW}x${paperH}"
        b.txtVersion.text = "Z1 Label — versi ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        Updater.onLog = { Logger.log(this, "update", it) }
        setPrinterLabel()
        // tap status printer = ganti protokol TSPL <-> ESC (disimpan)
        b.lblStatus.setOnClickListener {
            proto = if (proto == "tspl") "esc" else "tspl"
            prefs.edit().putString("proto", proto).apply()
            b.lblStatus.text = "Protokol: ${if (proto == "tspl") "TSPL (label printer)" else "ESC/POS raster"}"
            Logger.log(this, "proto", "ganti ke $proto")
        }

        adapter = ProdukAdapter { id ->
            if (id in selected) selected.remove(id) else selected.add(id)
            adapter.updateSelected(selected)
            b.btnCetak.isEnabled = selected.isNotEmpty()
        }
        b.listProduk.layoutManager = LinearLayoutManager(this)
        b.listProduk.adapter = adapter

        b.btnLogin.setOnClickListener { doLogin() }
        b.txtCari.doAfterTextChanged { filterList(it?.toString() ?: "") }
        b.btnRefresh.setOnClickListener { loadProduk() }
        b.btnLogout.setOnClickListener {
            ZposApi.cookie = ""
            prefs.edit().remove("email").remove("sandi").apply()
            showLogin()
        }
        b.btnPrinter.setOnClickListener { pilihPrinter() }
        b.btnSort.setOnClickListener { pilihSort() }
        b.btnPaper.setOnClickListener { pilihKertas() }
        b.btnProto.setOnClickListener { toggleProto() }
        b.btnFont.setOnClickListener { toggleFont() }
        b.btnBarcode.setOnClickListener { toggleBarcode() }
        b.btnBcSrc.setOnClickListener { toggleBcSrc() }
        b.btnCetak.setOnClickListener { cetak() }
        b.btnCekUpdate.setOnClickListener { cekUpdate(otomatis = false) }
        b.btnKirimLog.setOnClickListener { kirimLog() }

        val savedEmail = prefs.getString("email", "")
        if (savedEmail != null && savedEmail.isNotEmpty()) {
            b.txtEmail.setText(savedEmail)
            b.txtSandi.setText(prefs.getString("sandi", ""))
            doLogin()
        } else {
            showLogin()
        }
    }

    private fun showLogin() {
        b.loginView.visibility = View.VISIBLE
        b.mainView.visibility = View.GONE
    }

    private fun showMain() {
        b.loginView.visibility = View.GONE
        b.mainView.visibility = View.VISIBLE
        loadProduk()
        cekUpdate(otomatis = true)
        // auto-connect printer tersimpan (konsep GPrinter: buka app = langsung terhubung)
        autoSambungPrinter()
    }

    /** Connect otomatis ke printer tersimpan di background; tidak butuh tekan. */
    private fun autoSambungPrinter() {
        val addr = prefs.getString("printer", null) ?: return
        if (BluetoothPrinter.connected()) { b.lblStatus.text = "Printer terhubung"; Logger.log(this, "bt", "sudah terhubung $addr"); return }
        if (!bluetoothOk(ask = true)) return   // minta izin BT dulu
        b.lblStatus.text = "Menghubungkan printer…"
        Logger.log(this, "bt", "auto-connect $addr")
        BluetoothPrinter.autoConnect(addr) { err ->
            runOnUiThread {
                if (err != null) {
                    Logger.log(this, "bt", "auto-connect GAGAL: $err")
                    b.lblStatus.text = "Printer tak terhubung: $err"
                } else {
                    Logger.log(this, "bt", "auto-connect sukses")
                }
            }
        }
    }

    /** Cek versi rilis GitHub; kalau ada baru, tawarkan unduh-install. */
    private fun cekUpdate(otomatis: Boolean) {
        scope.launch {
            val rilis = try {
                withContext(Dispatchers.IO) { Updater.cekTerbaru() }
            } catch (e: Exception) {
                null
            } ?: run {
                withContext(Dispatchers.Main) {
                    if (!otomatis) b.lblStatus.text = "Gagal cek update / belum ada rilis"
                    Logger.log(this@MainActivity, "update", "gagal cek rilis: ${Updater.lastErr ?: "null"}")
                }
                return@launch
            }
            val cur = BuildConfig.VERSION_NAME
            val adaBaru = Updater.lebihBaru(rilis.versi, cur)
            // log hasil cek utk memudahkan diagnosa "notif update muncul padahal sudah terbaru"
            if (adaBaru) {
                Logger.log(this@MainActivity, "update",
                    "NOTIF update: rilis='${rilis.versi}' app='$cur' apkUrl=${rilis.apkUrl ?: "TIDAK ADA"}")
            }
            withContext(Dispatchers.Main) {
                if (!adaBaru) {
                    if (!otomatis) b.lblStatus.text = "Sudah versi terbaru ($cur)"
                    return@withContext
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update tersedia")
                    .setMessage("Versi ${rilis.versi} (sekarang $cur).\nDownload & install?")
                    .setPositiveButton("Unduh") { _, _ ->
                        val url = rilis.apkUrl
                        if (url == null) { Toast.makeText(this@MainActivity, "APK tidak tersedia", Toast.LENGTH_SHORT).show() }
                        else scope.launch {
                            withContext(Dispatchers.Main) { b.lblStatus.text = "Mengunduh APK..." }
                            val ok = try {
                                Updater.unduhDanInstall(this@MainActivity, url)
                            } catch (e: Exception) {
                                Updater.lastErr = "unduh: ${e::class.simpleName}: ${e.message}"
                                false
                            }
                            withContext(Dispatchers.Main) {
                                Logger.log(this@MainActivity, "update",
                                    if (ok) "unduh selesai — installer dibuka (v${rilis.versi}); tekan Install utk terpasang"
                                    else "unduh GAGAL: ${Updater.lastErr ?: url}")
                                b.lblStatus.text = if (ok) "Installer dibuka — tekan Install utk terpasang" else "Unduh gagal: ${Updater.lastErr ?: ""}"
                            }
                        }
                    }
                    .setNegativeButton("Nanti", null)
                    .show()
            }
        }
    }

    private fun doLogin() {
        val email = b.txtEmail.text.toString().trim()
        val sandi = b.txtSandi.text.toString()
        if (email.isEmpty() || sandi.isEmpty()) {
            b.lblLogin.text = "Isi email & password"
            return
        }
        b.lblLogin.text = "Memproses..."
        scope.launch {
            val err = ZposApi.login(email, sandi)
            withContext(Dispatchers.Main) {
                if (err != null) {
                    Logger.log(this@MainActivity, "login", "gagal: $err")
                    b.lblLogin.text = "Gagal: $err"
                } else {
                    Logger.log(this@MainActivity, "login", "sukses $email")
                    prefs.edit().putString("email", email).putString("sandi", sandi).apply()
                    b.lblLogin.text = ""
                    showMain()
                }
            }
        }
    }

    private fun loadProduk() {
        b.lblStatus.text = "Memuat produk..."
        scope.launch {
            val res = ZposApi.daftarProduk()
            withContext(Dispatchers.Main) {
                res.onSuccess { list ->
                    semua = list.toMutableList()
                    selected.clear()
                    val kosong = list.count { it.nama.isBlank() }
                    Logger.log(this@MainActivity, "load", "produk=${list.size}, nama-kosong=$kosong")
                    filterList(b.txtCari.text.toString())
                    b.btnCetak.isEnabled = false
                    b.lblStatus.text = "${semua.size} produk"
                }.onFailure { e ->
                    Logger.log(this@MainActivity, "load", "gagal: ${e.message}")
                    b.lblStatus.text = "Gagal muat: ${e.message}"
                }
            }
        }
    }

    private fun filterList(q: String) {
        val t = q.trim().lowercase()
        val base = if (t.isEmpty()) semua.toMutableList()
        else semua.filter { it.nama.lowercase().contains(t) || (it.barcode?.contains(t) == true) }.toMutableList()
        tampil = when (sortMode) {
            "baru" -> base.sortedByDescending { it.id }      // id tinggi = dibuat baru
            else -> base.sortedBy { it.nama.lowercase() }     // A-Z
        }.toMutableList()
        adapter.submit(tampil)
    }

    /** Dialog pilihan urutan: Terbaru / Nama A-Z. */
    private fun pilihSort() {
        val opt = arrayOf("Terbaru", "Nama A-Z")
        val cur = if (sortMode == "baru") 0 else 1
        AlertDialog.Builder(this)
            .setTitle("Urutkan Produk")
            .setSingleChoiceItems(opt, cur) { d, i ->
                sortMode = if (i == 0) "baru" else "nama"
                prefs.edit().putString("sort", sortMode).apply()
                b.btnSort.text = "Urut: " + opt[i]
                filterList(b.txtCari.text.toString())
                d.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Dialog ukuran kertas label; disimpan utk cetak. */
    private fun pilihKertas() {
        val sizes = arrayOf(
            "Label 25x15 mm", "Label 30x20 mm", "Label 40x20 mm", "Label 50x25 mm"
        )
        val vals = arrayOf("25x15", "30x20", "40x20", "50x25")
        val cur = vals.indexOf("${paperW}x${paperH}").coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Ukuran Kertas Label")
            .setSingleChoiceItems(sizes, cur) { d, i ->
                val kv = vals[i].split("x")
                paperW = kv[0].toInt(); paperH = kv[1].toInt()
                prefs.edit().putString("paper", "${paperW}x${paperH}").apply()
                b.btnPaper.text = "Kertas ${paperW}x${paperH}"
                d.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pilihPrinter() {
        if (!bluetoothOk(true)) return
        tampilDialogPrinter()
    }

    /** Dialog pilih printer tunggal: daftar terpasang + (kalau scan) hasil discovery.
     *  Di-refresh dari scratch (setItems hanya ada di Builder, bukan instance AlertDialog). */
    private fun tampilDialogPrinter() {
        printerDialog?.let { if (it.isShowing) it.dismiss() }
        val builder = AlertDialog.Builder(this)
            .setNegativeButton("Tutup", null)
        isiDialogPrinter(builder)
        val dlg = builder.create()
        dlg.setOnDismissListener { if (!scanAktif) BluetoothPrinter.stopScan(applicationContext) }
        printerDialog = dlg
        dlg.show()
    }

    private fun isiDialogPrinter(builder: AlertDialog.Builder) {
        val bonded = BluetoothPrinter.pairedDevices()
        val scan = if (scanAktif) BluetoothPrinter.discoveredDevices() else emptyList()
        val merged = LinkedHashMap<String, BluetoothDevice>()
        (bonded + scan).forEach { merged[it.address] = it }
        val list = merged.values.toList()
        val names = list.map { it.name + "  ·  " + it.address }.toTypedArray()
    builder.setTitle(if (scanAktif) "Memindai… (${list.size})" else "Pilih Printer")
        builder.setItems(names) { _, i ->
            printerAddress = list[i].address
            prefs.edit().putString("printer", printerAddress).apply()
            setPrinterLabel()
        }
        builder.setNeutralButton(if (scanAktif) "Berhenti scan" else "🔍 Scan perangkat") { _, _ ->
            if (scanAktif) {
                scanAktif = false
                BluetoothPrinter.stopScan(applicationContext)
            } else {
                scanAktif = true
                BluetoothPrinter.startScan(applicationContext) { runOnUiThread { tampilDialogPrinter() } }
            }
            tampilDialogPrinter()
        }
    }

    override fun onPause() {
        super.onPause()
        BluetoothPrinter.stopScan(applicationContext)
        scanAktif = false
    }

    override fun onDestroy() {
        BluetoothPrinter.stopScan(applicationContext)
        BluetoothPrinter.onStateChange = null
        BluetoothPrinter.close()
        super.onDestroy()
    }

    /** Kirim log sesi ke WhatsApp (pastikan WA terinstall); fallback ke chooser umum. */
    private fun kirimLog() {
        val crash = CrashReport.ambil(this)
        val teks = Logger.ambil(this) + "\nPrinter: " + (printerAddress ?: "-") +
            "\nStatus: " + if (BluetoothPrinter.connected()) "terhubung" else "putus" +
            if (crash.isNotBlank()) "\n\n=== CRASH TERAKHIR ===\n" + crash.trim() else ""
        // endpoint log VPS -> GitHub djvpri/z1label-logs (ku/agent bisa akses utk perbaikan)
        val body = JSONObject()
            .put("key", "z1label-secret-8f3a")
            .put("versi", BuildConfig.VERSION_NAME)
            .put("pesan", teks)
        b.lblStatus.text = "Mengirim log..."
        scope.launch {
            val err = try {
                val conn = URL("http://103.93.129.94:8123/log").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setFixedLengthStreamingMode(body.toString().toByteArray().size)
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    if (conn.responseCode != 200) "HTTP ${conn.responseCode}" else null
                } finally { conn.disconnect() }
            } catch (e: Exception) { "${e::class.simpleName}: ${e.message}" }
            withContext(Dispatchers.Main) {
                if (err == null) {
                    Logger.log(this@MainActivity, "log", "terkirim v${BuildConfig.VERSION_NAME}")
                    b.lblStatus.text = "Log terkirim"
                    Toast.makeText(this@MainActivity, "Log terkirim ke server", Toast.LENGTH_SHORT).show()
                } else {
                    Logger.log(this@MainActivity, "log", "gagal terkirim: $err")
                    b.lblStatus.text = "Gagal kirim log ($err)"
                    Toast.makeText(this@MainActivity, "Gagal kirim: $err", Toast.LENGTH_LONG).show()
                    // fallback manual via WhatsApp/share
                    val ii = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, teks) }
                    runCatching { startActivity(Intent.createChooser(ii, "Kirim Log Z1 Label")) }
                }
            }
        }
    }

    private fun btnProtoLabel() =
        if (proto == "tspl") "TSPL" else "ESC"

    private fun fontLabel(m: Int) = when (m) { 1 -> "S"; 2 -> "M"; else -> "L" }

    private fun toggleFont() {
        val opt = arrayOf("S (kecil)", "M (sedang)", "L (besar)")
        val cur = (fontMul - 1).coerceIn(0, 2)
        AlertDialog.Builder(this)
            .setTitle("Ukuran Huruf Nama & Harga")
            .setSingleChoiceItems(opt, cur) { d, i ->
                fontMul = i + 1
                prefs.edit().putInt("fontMul", fontMul).apply()
                b.btnFont.text = "Font: " + fontLabel(fontMul)
                Logger.log(this, "font", "ukuran ${fontLabel(fontMul)} (x${fontMul})")
                d.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun barcodeBtnLabel() = if (barcodeMode == "2d") "2D" else "1D"

    private fun toggleBarcode() {
        val opt = arrayOf("1D (barcode garis)", "2D (QR code)")
        val cur = if (barcodeMode == "2d") 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Jenis Barcode")
            .setSingleChoiceItems(opt, cur) { d, i ->
                barcodeMode = if (i == 0) "1d" else "2d"
                prefs.edit().putString("barcodeMode", barcodeMode).apply()
                b.btnBarcode.text = barcodeBtnLabel()
                Logger.log(this, "barcode", "mode ${barcodeBtnLabel()}")
                Toast.makeText(this, "Barcode: ${barcodeBtnLabel()}", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun bcSrcBtnLabel() = if (bcSrc == "13") "13 digit" else "6 digit"

    private fun toggleBcSrc() {
        val opt = arrayOf("6 digit (barcode_internal)", "13 digit (barcode asli)")
        val cur = if (bcSrc == "13") 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Cetak barcode mana?")
            .setSingleChoiceItems(opt, cur) { d, i ->
                bcSrc = if (i == 0) "6" else "13"
                prefs.edit().putString("bcSrc", bcSrc).apply()
                b.btnBcSrc.text = bcSrcBtnLabel()
                Logger.log(this, "barcode", "sumber ${bcSrcBtnLabel()}")
                Toast.makeText(this, "Cetak barcode: ${bcSrcBtnLabel()}", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun setPrinterLabel() {
        b.btnProto.text = btnProtoLabel()
        b.btnPrinter.text = "Printer: " + (printerAddress?.take(6)?.let { "…$it" } ?: "belum pilih")
        BluetoothPrinter.onStateChange = { ok ->
            runOnUiThread {
                if (ok) Logger.log(this, "bt", "terhubung")
                else Logger.log(this, "bt", "terputus")
                b.lblStatus.text = if (ok) "Printer terhubung" else "Printer terputus — cetak utk sambung ulang"
                setPrinterLabel()
            }
        }

    }

    private fun toggleProto() {
        val opt = arrayOf("TSPL (printer label)", "ESC/POS (raster)")
        val cur = if (proto == "tspl") 0 else 1
        AlertDialog.Builder(this)
            .setTitle("Protokol Printer")
            .setSingleChoiceItems(opt, cur) { d, i ->
                proto = if (i == 0) "tspl" else "esc"
                prefs.edit().putString("proto", proto).apply()
                b.btnProto.text = btnProtoLabel()
                setPrinterLabel()
                Logger.log(this, "proto", "ganti ke $proto")
                b.lblStatus.text = "Protokol: ${btnProtoLabel()} — cetak utk sambung ulang"
                d.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun bluetoothOk(ask: Boolean): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Toast.makeText(this, "Perangkat tak punya Bluetooth", Toast.LENGTH_SHORT).show(); return false
        }
        val needsConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        if (needsConnect && ask) {
            val perms = if (Build.VERSION.SDK_INT >= 31)
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            else arrayOf(Manifest.permission.BLUETOOTH_ADMIN)
            permReq.launch(perms)
            return false
        }
        if (!adapter.isEnabled && ask) {
            startActivityForResult(android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 1)
        }
        return true
    }

    private fun cetak() {
        val addr = printerAddress ?: run {
            Toast.makeText(this, "Pilih printer dulu", Toast.LENGTH_SHORT).show(); return
        }
        b.btnCetak.isEnabled = false
        b.lblStatus.text = "Persiapan..."
        if (!bluetoothOk(true)) { b.btnCetak.isEnabled = true; return }
        scope.launch {
            val pilih = tampil.filter { it.id in selected }
            if (pilih.isEmpty()) { withContext(Dispatchers.Main){ b.btnCetak.isEnabled=true; b.lblStatus.text="Pilih produk dulu" }; return@launch }

            Logger.log(this@MainActivity, "cetak", "mulai ${pilih.size} label, kertas ${paperW}x${paperH}mm, print ${printerAddress ?: "-"}")

            // preview dulu sebelum mencetak (render bitmap label pertama, konfirmasi user)
            val labelPertama = pilih.first()
            val previewBmp = try {
                val bcP = barcodeLabel(labelPertama, bcSrc == "13")
                EscPosLabel.previewBitmap(labelPertama.nama, labelPertama.harga, bcP, paperW, paperH, barcode2d = barcodeMode == "2d")
            } catch (e: Exception) { null }
            if (previewBmp != null) {
                val lanjut = konfirmasiPrintPreview(previewBmp, pilih.size)
                if (!lanjut) {
                    withContext(Dispatchers.Main) {
                        b.lblStatus.text = "Cetak dibatalkan (pratinjau)"; b.btnCetak.isEnabled = true
                    }
                    return@launch
                }
            }

            // konek bila belum (auto-connect mungkin sudah buat); socket dijaga hidup antar cetak
            if (!BluetoothPrinter.connected()) {
                val errConn = BluetoothPrinter.connect(addr)
                if (errConn != null) {
                    Logger.log(this@MainActivity, "cetak", "gagal konek $addr: $errConn")
                    withContext(Dispatchers.Main){
                        b.lblStatus.text = "Gagal konek: $errConn"; b.btnCetak.isEnabled = true
                    }; return@launch
                }
            }

            // bangun + kirim label; tiap tahap di-cover try/catch utk tangkap kenapa gagal
            try {
                val run = try {
                    if (proto == "tspl") {
                        // printer label (clabel, dll) : TSPL — printer render text+barcode sendiri
                        val data = pilih.map { p ->
                            val bc = barcodeLabel(p, bcSrc == "13")
                            EscPosLabel.LabelT(p.nama, p.harga, bc)
                        }
                        EscPosLabel.buatRunTSPL(data, paperW, paperH, includeNama = paperH <= 20, fontMul = fontMul, barcode2d = barcodeMode == "2d")
                    } else {
                        val bmpList = pilih.mapIndexed { idx, p ->
                            try {
                                val bc = barcodeLabel(p, bcSrc == "13")
                                EscPosLabel.buatLabel(p.nama, p.harga, bc, paperW, paperH)
                            } catch (e: Exception) {
                                Logger.log(this@MainActivity, "cetak",
                                    "gagal bulat-label #$idx (${p.nama} id ${p.id} bc ${p.barcode}): ${e::class.simpleName}: ${e.message}")
                                throw e
                            }
                        }
                        EscPosLabel.buatRun(bmpList)
                    }
                } catch (e: Exception) {
                    Logger.log(this@MainActivity, "cetak", "gagal buatRun($proto): ${e::class.simpleName}: ${e.message}")
                    throw e
                }
                val errWr = BluetoothPrinter.write(run)
                val versiApp = "v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
                if (errWr != null) {
                    Logger.log(this@MainActivity, "cetak", "[$versiApp] kirim GAGAL: $errWr")
                } else {
                    val hex = run.take(8).joinToString("") { "%02X".format(it) }
                    Logger.log(this@MainActivity, "cetak",
                        "[$versiApp] kirim OK ${pilih.size} label, ${run.size} byte, proto=$proto, head[0x$hex], connected=${BluetoothPrinter.connected()}")
                }
                withContext(Dispatchers.Main) {
                    b.lblStatus.text = if (errWr != null) "Cetak gagal: $errWr"
                        else "Cetak ${pilih.size} label ✓ (barcode baru otomatis utk yg kosong)"
                    b.btnCetak.isEnabled = true
                }
            } catch (e: Exception) {
                // tangkap apa pun (buat-label, buatRun, write) — jangan biarkan bocor -> crash app
                Logger.log(this@MainActivity, "cetak", "EXCEPTION: ${e::class.simpleName}: ${e.message}")
                withContext(Dispatchers.Main) {
                    b.lblStatus.text = "Cetak gagal: ${e.message ?: e::class.simpleName}"
                    b.btnCetak.isEnabled = true
                }
            }
            // socket TIDAK ditutup: biar cetak berikutnya langsung (hemat waktu koneksi)
        }
    }

    /**
     * Tampilkan pratinjau (bitmap) + tombol Cetak/Batal sebelum kirim ke printer.
     * suspend: menanti pilihan user; true = lanjut cetak, false = batal.
     */
    private suspend fun konfirmasiPrintPreview(bitmap: Bitmap, jumlah: Int): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val img = ImageView(this@MainActivity).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    maxWidth = (resources.displayMetrics.density * 260).toInt()
                    maxHeight = (resources.displayMetrics.density * 200).toInt()
                    setPadding(24, 16, 24, 0)
                }
                val dlg = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Pratinjau — Cetak $jumlah label?")
                    .setView(img)
                    .setPositiveButton("Cetak") { _, _ -> if (cont.isActive) cont.resume(true) }
                    .setNegativeButton("Batal") { _, _ -> if (cont.isActive) cont.resume(false) }
                    .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                    .create()
                dlg.show()
            }
        }

    // ---- Adapter ----
    class ProdukAdapter(private val onToggle: (Long) -> Unit) :
        RecyclerView.Adapter<ProdukAdapter.VH>() {

        private var items: List<Produk> = emptyList()
        private var selected: Set<Long> = emptySet()
        fun submit(l: List<Produk>) { items = l; notifyDataSetChanged() }
        fun updateSelected(s: Set<Long>) { selected = s; notifyDataSetChanged() }

        class VH(val r: ItemProdukBinding) : RecyclerView.ViewHolder(r.root)

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
            VH(ItemProdukBinding.inflate(LayoutInflater.from(p.context), p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = items[pos]
            h.r.chk.isChecked = p.id in selected
            val nm = p.nama.trim()
            if (nm.isEmpty()) Logger.log(h.itemView.context, "produk", "nama-kosong id=${p.id} (idx=$pos)")
            h.r.txtNama.text = nm.ifEmpty { "Produk #${if (p.id > 0) p.id else pos + 1}" }
            h.itemView.setOnClickListener { onToggle(p.id) }
        }
    }
}
