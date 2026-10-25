# SPD Tool — Android

Aplikasi Android untuk **flashing firmware `.pac` chipset Unisoc/Spreadtrum lewat kabel USB OTG**.
Padanan fungsi inti *SPD Upgrade Tool* (Windows) di HP Android, tanpa PC.

> ⚠️ **Tidak ada fitur bypass / exploit.** Aplikasi ini **tidak** mengakali autentikasi BROM,
> tidak melakukan *crash* bootrom, dan tidak melewati proteksi apa pun. Perangkat harus
> memang sudah berada dalam **mode download (BROM/FDL)** dan firmware harus cocok dengan
> model perangkat. Salah firmware bisa membuat perangkat tidak bisa boot.

---

## Fitur

| Fitur | Status |
|---|---|
| Deteksi perangkat Unisoc via USB OTG (VID `0x1782`) | ✅ |
| Inspeksi file `.pac` (header, entri, XML, CRC) | ✅ |
| Pilih partisi yang akan di-flash | ✅ |
| Flash: FDL1 → FDL2 → ExecNandInit → START/MIDST/END_DATA → reset | ✅ |
| Repartition (opsional, harus diaktifkan manual) | ✅ |
| Backup / baca partisi ke file `.img` | ✅ |
| Baca Chip UID | ✅ |

Tidak termasuk: bypass auth, crash PL, format manual, MES/IMEI, unlock.

---

## Cara pakai

1. **Siapkan HP target** dalam mode download:
   - Matikan HP, lalu tahan tombol volume (kombinasi berbeda per model) saat colok kabel.
   - Atau lewat ADB: `adb reboot edl` (bila didukung).
2. **Sambungkan** HP target ke HP Android yang menjalankan aplikasi ini memakai
   kabel **USB OTG**. HP Android harus mendukung USB Host.
3. Buka aplikasi → izinkan akses USB saat dialog muncul.
   Status akan berubah jadi `BROM` atau `FDL`.
4. Tekan **Pilih .pac**, pilih file firmware.
5. Tekan **Inspeksi PAC** untuk memastikan FDL1/FDL2 ada dan CRC valid.
6. Centang partisi yang ingin ditulis, lalu tekan **FLASH**.
7. **Jangan cabut kabel** sampai progress mencapai 100%.

Backup partisi: pilih **satu** partisi → **Backup Partisi** → file tersimpan di
`Android/data/com.unisoc.spdflasher/files/`.

---

## Build

Butuh JDK 17 + Android SDK (platform 34, build-tools 34.0.0).

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Tanpa wrapper pun bisa: `gradle :app:assembleDebug`.

Tanpa dependensi eksternal — hanya Android framework.

---

## Struktur kode

| File | Isi |
|---|---|
| `PacParser.java` | Parser container `.pac`: header 2124 B, tabel entri 2580 B/entri, blok XML, CRC-16 |
| `Bsl.java` | Protokol BSL: framing HDLC (escape `0x7E`/`0x7D`), CRC-16/CCITT & sum16, kode perintah/respon |
| `UsbTransport.java` | Transport bulk USB (VID `0x1782`), deteksi BROM/FDL, klaim interface |
| `FlashEngine.java` | Alur flashing lengkap + baca partisi + baca UID |
| `MainActivity.java` | UI: pemilihan perangkat, PAC, partisi, progress, log |

Detail format & protokol: [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## Pengujian

Test berjalan di JVM (tanpa perangkat):

```bash
cd tools
javac -cp kxml2.jar -d out ../app/src/main/java/com/unisoc/spdflasher/PacParser.java \
      ../app/src/main/java/com/unisoc/spdflasher/Bsl.java PacTest.java BslLoopTest.java
java -cp out:kxml2.jar BslLoopTest      # 13 tes framing/checksum
python3 mkpac.py                        # buat .pac sintetis
java -cp out:kxml2.jar PacTest test/fake.pac
```

`mkpac.py` membuat `.pac` sintetis (header + 6 entri + XML + data + CRC) untuk
memverifikasi parser tanpa file firmware asli.

Hasil uji framing: 13/13 PASS (escape, sum16, CRC, frame terpotong, byte sampah,
dua frame berurutan, batas 65535 byte).

---

## Batasan yang perlu diketahui

- **Butuh USB Host + OTG.** Banyak HP Android punya host mode, tapi tidak semua bisa
  memberi daya cukup ke perangkat yang sedang di BROM.
- **Kecepatan** terbatas bandwidth USB OTG (umumnya jauh lebih lambat dari PC).
- **Besar file** — PAC > 2 GB butuh ruang cache; aplikasi menyalin PAC ke cache dulu.
- **Cocokkan model.** Layout partisi berbeda antar model; repartition hanya untuk
  kasus di mana Anda yakin firmware memang untuk perangkat itu.

---

## Lisensi

MIT — lihat [LICENSE](LICENSE).

Proyek ini hasil implementasi bersih (clean-room) dari format container dan protokol
yang didokumentasikan publik. Tidak memuat kode atau biner milik vendor.
