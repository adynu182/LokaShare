# LokaShare

LokaShare adalah aplikasi pelacakan lokasi berbasis Android yang dirancang untuk membantu pengguna mencatat dan mengirimkan data posisi secara otomatis. Aplikasi ini cocok digunakan untuk kebutuhan pemantauan lokasi, pelacakan pergerakan, serta sinkronisasi data ke Firebase Firestore.

## Fitur utama

- Pelacakan lokasi berbasis foreground service
- Pengambilan lokasi dengan akurasi yang lebih baik melalui beberapa percobaan
- Penyimpanan data lokasi secara lokal saat offline
- Sinkronisasi data otomatis ke Firestore saat koneksi tersedia
- Dukungan autentikasi anonim Firebase
- Penyimpanan preferensi pengguna seperti nama, ID perangkat, dan status tracking

## Alur kerja aplikasi

1. Aplikasi mengambil lokasi terbaru melalui layanan lokasi Android.
2. Lokasi dipilih berdasarkan kualitas data, termasuk akurasi dan usia data.
3. Jika koneksi internet tersedia, data dikirim ke Firestore.
4. Jika sedang offline, data disimpan terlebih dahulu ke database lokal Room.
5. Saat koneksi kembali tersedia, data pending akan disinkronkan ke Firestore secara otomatis.

## Arsitektur singkat

- Android foreground service untuk pelacakan berkelanjutan
- Repository lokasi untuk mengambil data posisi
- Repository Firestore untuk mengirim dan menyinkronkan data
- Room Database untuk antrian data offline
- SharedPreferences untuk menyimpan state aplikasi dan preferensi pengguna

## Teknologi yang digunakan

- Kotlin
- AndroidX
- Room Database
- Firebase Authentication
- Firebase Firestore
- Google Play Services Location
- Coroutines

## Tujuan aplikasi

LokaShare bertujuan memberikan solusi sederhana namun andal untuk mengumpulkan data lokasi secara otomatis, menjaga data tetap aman saat offline, dan memastikan sinkronisasi data saat koneksi kembali tersedia.

