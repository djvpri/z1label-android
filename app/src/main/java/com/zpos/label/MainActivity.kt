package com.zpos.label

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.zpos.label.bc.Code128
import com.zpos.label.bt.BluetoothPrinter
import com.zpos.label.databinding.ActivityMainBinding
import com.zpos.label.databinding.ItemProdukBinding
import com.zpos.label.escpos.EscPosLabel
import com.zpos.label.update.Updater
import com.zpos.label.util.CrashReport
import com.zpos.label.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val paper = (prefs.getString("paper", "25x15") ?: "25x15").split("x")
        paperW = paper.getOrNull(0)?.toIntOrNull() ?: 25
        paperH = paper.getOrNull(1)?.toIntOrNull() ?: 15
        b.btnSort.text = if (sortMode == "baru") "Urut: Terbaru" else "Urut: Nama A-Z"
        b.btnPaper.text = "Kertas ${paperW}x${paperH}"
        b.txtVersion.text = "Z1 Label — versi ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
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
        b.btnCetak.setOnClickListener { cetak() }
        b.btnCekUpdate.setOnClickListener { cekUpdate(otomatis = false) }
        b.btnKirimLog.setOnClickListener { kirimLogWa() }

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
                                    if (ok) "unduh+install berhasil"
                                    else "unduh/install GAGAL: ${Updater.lastErr ?: url}")
                                b.lblStatus.text = if (ok) "Unduh selesai — install di sistem" else "Unduh gagal: ${Updater.lastErr ?: ""}"
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
    private fun kirimLogWa() {
        val crash = CrashReport.ambil(this)
        val teks = Logger.ambil(this) + "\nPrinter: " + (printerAddress ?: "-") +
            "\nStatus: " + if (BluetoothPrinter.connected()) "terhubung" else "putus" +
            if (crash.isNotBlank()) "\n\n=== CRASH TERAKHIR ===\n" + crash.trim() else ""
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, teks)
            setPackage("com.whatsapp")
        }
        try {
            startActivity(i)
        } catch (_: Exception) {
            // WA tak terinstall: lempar ke app share apa pun (Email, dsb)
            runCatching { startActivity(Intent.createChooser(i, "Kirim Log Z1 Label")) }
        }
    }

    private fun setPrinterLabel() {
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
                            val bc = if (p.barcode != null && p.barcode.length == 6 && p.barcode.all { it.isDigit() })
                                p.barcode else Code128.generateV3(p.id)
                            EscPosLabel.LabelT(p.nama, p.harga, bc)
                        }
                        EscPosLabel.buatRunTSPL(data, paperW, paperH)
                    } else {
                        val bmpList = pilih.mapIndexed { idx, p ->
                            try {
                                val bc = if (p.barcode != null && p.barcode.length == 6 && p.barcode.all { it.isDigit() })
                                    p.barcode else Code128.generateV3(p.id)
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
            h.r.txtNama.text = p.nama
            h.r.txtBarcode.text = p.barcode?.let { "Barcode: $it" } ?: "Barcode: otomatis"
            h.itemView.setOnClickListener { onToggle(p.id) }
        }
    }
}
