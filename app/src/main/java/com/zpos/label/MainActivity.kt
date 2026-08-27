package com.zpos.label

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.IO)

    private var semua: MutableList<Produk> = mutableListOf()
    private var tampil: MutableList<Produk> = mutableListOf()
    private val selected = HashSet<Long>()
    private lateinit var adapter: ProdukAdapter

    private var printerAddress: String? = null

    private val permReq =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // cukup coba start; sesudah grant user pilih via bluetooth isEnabled
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = getSharedPreferences("z1label", Context.MODE_PRIVATE)
        printerAddress = prefs.getString("printer", null)
        setPrinterLabel()

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
        b.btnCetak.setOnClickListener { cetak() }
        b.btnCekUpdate.setOnClickListener { cekUpdate(otomatis = false) }

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
    }

    /** Cek versi rilis GitHub; kalau ada baru, tawarkan unduh-install. */
    private fun cekUpdate(otomatis: Boolean) {
        scope.launch {
            val rilis = withContext(Dispatchers.IO) { Updater.cekTerbaru() } ?: run {
                withContext(Dispatchers.Main) {
                    if (!otomatis) b.lblStatus.text = "Gagal cek update / belum ada rilis"
                }
                return@launch
            }
            val cur = BuildConfig.VERSION_NAME
            val adaBaru = Updater.lebihBaru(rilis.versi, cur)
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
                            b.lblStatus.text = "Mengunduh APK..."
                            val ok = Updater.unduhDanInstall(this@MainActivity, url)
                            withContext(Dispatchers.Main) {
                                b.lblStatus.text = if (ok) "Unduh selesai — install di sistem" else "Unduh gagal"
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
                    b.lblLogin.text = "Gagal: $err"
                } else {
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
                    b.lblStatus.text = "Gagal muat: ${e.message}"
                }
            }
        }
    }

    private fun filterList(q: String) {
        val t = q.trim().lowercase()
        tampil = if (t.isEmpty()) semua.toMutableList()
        else semua.filter { it.nama.lowercase().contains(t) || (it.barcode?.contains(t) == true) }.toMutableList()
        adapter.submit(tampil)
    }

    private fun pilihPrinter() {
        if (!bluetoothOk(true)) return
        val devs = BluetoothPrinter.pairedDevices()
        if (devs.isEmpty()) {
            Toast.makeText(this, "Tidak ada printer Bluetooth terpasang", Toast.LENGTH_SHORT).show()
            return
        }
        val names = devs.map { it.name + " (" + it.address + ")" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih Printer")
            .setItems(names) { _, i ->
                printerAddress = devs[i].address
                prefs.edit().putString("printer", printerAddress).apply()
                setPrinterLabel()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun setPrinterLabel() {
        b.btnPrinter.text = "Printer: " + (printerAddress?.take(6)?.let { "…$it" } ?: "belum pilih")
        b.lblStatus.text = if (BluetoothPrinter.connected()) "Printer terhubung" else "Printer belum konek"
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

            val errConn = BluetoothPrinter.connect(addr)
            if (errConn != null) {
                withContext(Dispatchers.Main){
                    b.lblStatus.text = "Gagal konek: $errConn"; b.btnCetak.isEnabled = true
                }; return@launch
            }
            // bangun label: barcode v3 utk produk yang belum punya / bukan 6 digit numerik
            val bmpList = pilih.map { p ->
                val bc = if (p.barcode != null && p.barcode.length == 6 && p.barcode.all { it.isDigit() })
                    p.barcode else Code128.generateV3(p.id)
                EscPosLabel.buatLabel(p.nama, p.harga, bc, 25, 15)
            }
            val errWr = BluetoothPrinter.write(EscPosLabel.buatRun(bmpList))
            BluetoothPrinter.close()
            withContext(Dispatchers.Main) {
                b.lblStatus.text = if (errWr != null) "Cetak gagal: $errWr"
                    else "Cetak ${pilih.size} label ✓ (barcode baru otomatis utk yg kosong)"
                b.btnCetak.isEnabled = true
            }
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
            h.r.chk.text = "${p.nama}" + (p.barcode?.let { "  ·  $it" } ?: "")
            h.r.chk.isChecked = p.id in selected
            h.itemView.setOnClickListener { onToggle(p.id) }
        }
    }
}
