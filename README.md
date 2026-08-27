# Z1 Label — Android (cetak label barcode via Bluetooth ESC/POS)

App Android native ringan untuk **mencetak label barcode produk** langsung ke printer
label thermal Bluetooth (ESC/POS) dari data produk **Z1 Pos** (z1pos.zomet.my.id).

## Fitur
- Login dengan email + password **Z1 Pos** (token `zpos_token` disimpan; password
  disimpan lokal utk auto-login — simpan di perangkat aman sendiri).
- Unduh daftar produk dari `GET /api/produk?semua=1`.
- Cari & pilih (checklist) produk yang mau dicetak label-nya.
- Cetak beberapa label sekaligus: nama + harga + barcode **Code 128-C** (format
  internal z1pos v3, 6 digit) → satu bitmap label 25×15mm → kirim raster ESC/POS
  (`GS v 0`) ke printer Bluetooth.
- Produk yang belum punya barcode → digenerate otomatis (konsisten dgn
  `generateProductBarcode` v3 z1pos).

## Build
1. Buka folder ini di **Android Studio** (koala/Jellyfish lokal).
2. File → Sync Project with Gradle Files.
3. Build → Build Bundle(s) / APK(s) → Build APK.
4. APK di `app/build/outputs/apk/debug/` (atau release bila di-signature).

Butuh: JDK 17, Android SDK (compileSdk 34).

## Struktur
```
app/src/main/java/com/zpos/label/
  MainActivity.kt      # ui: login, daftar produk, pilih, cetak
  api/ZposApi.kt       # login (cookie zpos_token) + GET produk
  bc/Code128.kt        # generate barcode v3 + Code 128-C bit pattern (mirror zpos web)
  bt/BluetoothPrinter.kt # Bluetooth Classic SPP (RFCOMM) + tulis bytes
  escpos/EscPosLabel.kt  # bitmap label 25x15mm -> raster GS v 0
```

## Catatan
- Barcode di-render sbg **raster bitmap** (bukan font printer) → hasil identik di
  semua printer & tajam (pakai modul 2 dot minimum).
- Jika printer tak pakai UUID SPP standar, fallback refleksi `createRfcommSocket(1)`
  sudah disediakan.
- Minta runtime permission **Bluetooth** (Android 12+ BLUETOOTH_CONNECT/SCAN).

## Testing
Jalankan unit logika barcode via JVM (opsional): cek `bc/Code128` pakai JUnit local.
``` 
```
