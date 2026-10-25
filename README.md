# SPD Tool Android

Alat flashing firmware Unisoc/Spreadtrum lewat USB OTG dari HP Android.
Tanpa PC, tanpa root, tanpa driver.

## Peringatan

- Pakai kabel OTG yang bagus. Kabel/konektor jelek bikin proses putus di tengah
  jalan dan HP bisa berhenti di logo.
- Firmware harus cocok dengan perangkat. Salah firmware = HP tidak mau boot.
- Jangan cabut kabel saat proses berjalan.

## Fitur

- Baca dan periksa file firmware `.pac` (header, daftar partisi, CRC).
- Flash partisi yang dipilih saja, atau seluruh firmware.
- Repartisi tabel partisi bila firmware membawa partisi baru.
- Backup (dump) partisi ke file di penyimpanan HP.
- Baca UID chip.
- Paksa masuk mode fastboot: menulis perintah boot ke partisi `misc` lalu
  restart, dipakai kalau HP berhenti di logo setelah flashing.
- Menu fastboot: cek info perangkat, reboot ke sistem/fastboot/recovery,
  lanjutkan boot.

## Cara Penggunaan

1. Pasang kabel OTG, sambungkan HP target dalam mode download
   (tekan volume bawah + colok kabel, atau lewat menu developer).
2. Beri izin akses USB saat diminta.
3. Pilih file `.pac`.
4. Tekan "Periksa" untuk melihat isi firmware dan status CRC.
5. Pilih partisi, lalu tekan "Flash".

## Uji Coba

Diuji dengan Infinix Smart 10 (X6725) sebagai HP target dan Vivo Y19S sebagai
HP pengganti PC. Pembacaan firmware, daftar partisi, dan CRC sudah sesuai
dengan firmware asli. Flashing penuh belum diuji sampai selesai.

## Build

    ./gradlew assembleRelease

APK ada di `app/build/outputs/apk/release/app-release.apk`.
