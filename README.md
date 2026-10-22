# MTK Flash

Aplikasi Android untuk berbicara langsung dengan perangkat MediaTek lewat
kabel USB OTG — tanpa PC, tanpa driver Windows, tanpa SP Flash Tool.

Dua jalur yang tersedia:

| Jalur | Untuk apa | Butuh auth? |
|---|---|---|
| **BROM / DA** | baca info chip, baca partisi, flash lewat scatter | ya, untuk menulis |
| **Fastboot** | flash partisi setelah bootloader dibuka | tidak (bootloader sudah terbuka) |

> **Aplikasi ini tidak menembus autentikasi.** Tidak ada modul SLA/DAA
> bypass, tidak ada patch DA, tidak ada payload exploit BROM. Kalau
> perangkat meminta file auth, aplikasi akan berhenti dan melaporkan
> error yang dikembalikan chip. Untuk X6840/X6878 dan MediaTek Secure
> Boot lain, jalur yang benar adalah buka bootloader lalu pakai Fastboot.

---

## Isi repo

```
src/com/rizky/mtkflash/
  UsbTransport.java       transport USB mentah (bulk OUT / bulk IN)
  BromProtocol.java       perintah Boot ROM MediaTek
  DaProtocol.java         perintah Download Agent
  DaLoader.java           pembaca DA tunggal + MTK_AllInOne_DA.bin
  ScatterParser.java      pembaca file scatter (tata letak partisi)
  FastbootProtocol.java   klien Fastboot (download:/flash:)
  MainActivity.java       UI + alur kerja

res/                      string + filter USB (VID 0x0E8D)
test/                     uji parser scatter & DA (Java murni, tanpa Android)
release/mtkflash.apk      APK siap pasang
build.sh                  build tanpa Gradle
```

Ukuran source: ~1.860 baris Java, 7 kelas.

## Unduh

APK ada di [`release/mtkflash.apk`](release/mtkflash.apk).

- Paket: `com.rizky.mtkflash`
- minSdk 24 (Android 7), targetSdk 34
- Ditandatangani keystore **debug** — untuk pemakaian sendiri, bukan
  distribusi ke Play Store
- SHA-256: `f8d93a9b002bf05f0e0cdeb954f37502206aad94b11b6ef8ff2d620ae425e7a3`

Pasang: aktifkan "Install unknown apps" untuk file manager, lalu buka APK-nya.

## Cara pakai

1. Colok kabel OTG, sambungkan HP target.
2. Buka aplikasi → **Scan** untuk melihat perangkat yang terdeteksi.
3. Pilih jalur:

**BROM/DA (diagnostik & baca)**
- **Connect** — handshake BROM, baca versi chip
- **Info chip** — hardware code, versi BROM, status target config
- **Pilih DA** → **Unggah DA** — kirim DA ke SRAM perangkat
- **Baca partisi** — baca region eMMC/UFS ke file
- **Pilih scatter** → **Flash** — tulis partisi sesuai scatter

**Fastboot (setelah bootloader dibuka)**
- **FB scan** → **FB info** — `getvar:all`
- Isi nama partisi (default `vendor_dlkm`) → pilih image → **FB flash**

## Build sendiri

Butuh Android SDK (build-tools + platform android-34) dan JDK 17/21.

```bash
ANDROID_SDK_ROOT=/opt/android-sdk ./build.sh
```

Tanpa Gradle: `aapt2` → `javac` → `d8` → `zipalign` → `apksigner`.
Keystore debug dibuat otomatis di `debug.keystore` dan tidak ikut di-commit.

## Uji parser tanpa HP

`test/ParserTest.java` menguji `ScatterParser` dan `DaLoader` dengan Java
biasa — tidak butuh perangkat atau emulator:

```bash
javac -d test/out test/ParserTest.java src/com/rizky/mtkflash/ScatterParser.java src/com/rizky/mtkflash/DaLoader.java
java -cp test/out ParserTest
```

## Dari mana nilai protokolnya

Semua konstanta di `BromProtocol`, `DaProtocol`, dan `DaLoader` dibaca
langsung dari `libflashtool.v1.so` (SP Flash Tool 5.1916 Linux) dengan
disassembler — bukan dari tebakan atau dokumentasi pihak ketiga. Contoh
yang terverifikasi:

- Handshake BROM: byte `0xD1` di `BRom_AutoBoot::BRom_StartCmd`
- Format perintah: `[cmd:1][echo][len:4 BE][echo][payload][status:2]`
- Penanda DA di `MTK_AllInOne_DA.bin`: `MMM\x01` + header `FILE_INFO`
  (`+0x04` ukuran header `0x38`, `+0x08` string `FILE_INFO`)
- Fastboot: interface `0xFF/0x42/0x03`

## Batasan yang diketahui

- Belum diuji di perangkat fisik — handshake byte dan pemilihan DA per
  chip (MT6768/MT6769) belum diverifikasi lapangan.
- Hanya jalur USB OTG; tidak ada mode jaringan/ADB.
- Penulisan lewat BROM/DA pada perangkat Secure Boot akan gagal dengan
  `STATUS_SEC_AUTH_FILE_NEEDED (0xC0030012)` — itu perilaku yang benar,
  bukan bug yang perlu ditambal.
- Tidak ada fitur format, unlock, atau bypass proteksi.

## Peringatan

Alat ini menulis ke partisi penyimpanan. Salah partisi atau salah image
= brick. Pakai hanya pada perangkat milik sendiri, dan pastikan kamu punya
salinan firmware resmi sebelum menulis apa pun.

## Lisensi

MIT — lihat [LICENSE](LICENSE).
