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

## Rilis (untuk agent & manusia)

> **Agent:** prosedur lengkap ada di
> [`Z-Rooms-android/RILIS-AGENT.md`](https://github.com/djvpri/Z-Rooms-android/blob/main/RILIS-AGENT.md)
> — mencakup Z1 Label juga. Ringkasnya di bawah.

Rilis lewat GitHub Actions: **`.github/workflows/release.yml`**.

| Jalur | Butuh | Untuk siapa |
| --- | --- | --- |
| A. UI GitHub → Actions → release → Run workflow | hak tulis repo, nol token | manusia |
| B. REST `workflow_dispatch` | PAT scope `repo`+`workflow` | agent |
| C. `gradlew :app:assembleRelease` lokal | keystore lokal + SDK | darurat |

Input: `version` (wajib, mis. `1.6.33`) dan `changelog` (opsional).

**Agent yang tak punya token tak bisa jalur A** (itu klik di peramban). Minta
pemilik repo menjalankannya — jangan menebak langkahnya.

### Keystore

Hidup sebagai base64 di GitHub **Secrets** (`ANDROID_KEYSTORE_B64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEYSTORE_ALIAS`, `ANDROID_KEY_PASSWORD`).
Tak butuh berkas lokal. Runner menuliskannya sendiri ke `keystore.jks`.

Keystore berbeda = APK **tak bisa** dipasang menimpa yang terpasang ("app not
installed"). Pakai yang sama, selalu.

### Auto-update

`app/src/main/java/com/zpos/label/update/Updater.kt` menanyakan
`/repos/djvpri/z1label-android/releases/latest`, lalu membandingkan
**`tag_name`** dengan `BuildConfig.VERSION_NAME`.

Dua akibat yang perlu diketahui sebelum mengubah apa pun:

1. **Aset `.apk` WAJIB dilampirkan di Release.** Tag saja tak cukup — updater
   mencari aset APK. Release tanpa aset = update tak pernah terdeteksi.
2. **Perbandingan memakai `tag_name`, bukan `versionCode`.** Jadi `versionCode`
   (epoch detik, dinaikkan runner) hanya perlu **selalu naik**, bukan mencerminkan
   versi. Ini berbeda dari ZXRoom yang memutuskan update dari `versionCode`.

### `main` tak menyimpan versi terbit — ini disengaja

`app/build.gradle.kts` di `main` tetap `versionCode = 1` / `versionName = "1.0.0"`
meski rilis sudah jauh di atasnya. Penyebabnya: `release.yml` menaikkan versi
**hanya di runner**, tanpa langkah commit/push. Jadi:

- versi terbit **jangan** dibaca dari `main` — baca dari
  `/releases/latest` (`tag_name`)
- `versionCode` tak punya histori di git

**Jangan "perbaiki" ini dengan menyamakannya ke ZXRoom** (yang men-commit versi
ke `alamat.json`). Itu mengubah sumber kebenaran versi untuk app yang sudah
beredar di banyak perangkat, padahal mekanisme updatenya sekarang bekerja.
Kalau suatu saat `main` memang harus mencerminkan versi terbit, tambahkan langkah
commit balik di `release.yml` — jangan ubah cara updater membandingkan versi.

## Testing
Jalankan unit logika barcode via JVM (opsional): cek `bc/Code128` pakai JUnit local.
``` 
```
