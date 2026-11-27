package com.nex.fps;

// Antarmuka layanan yang berjalan sebagai "shell" lewat Shizuku.
interface IUserService {
    // Nomor transaksi 16777114 diwajibkan Shizuku untuk destroy().
    void destroy() = 16777114;

    // Menjalankan perintah shell dan mengembalikan keluarannya (stdout + stderr).
    String exec(String command) = 1;
}
