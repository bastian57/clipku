package com.daftech.clipku.youtube;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

/**
 * Adaptor Downloader NewPipeExtractor di atas OkHttp yang sudah dipakai
 * ClipKu untuk Innertube dan Groq.
 *
 * Ditulis sebagai file Java, bukan Kotlin, dengan sengaja: di lini
 * NewPipeExtractor 0.26.x, metode Downloader.execute() berubah visibilitas
 * dari public menjadi protected di beberapa revisi. Override Java dengan
 * visibilitas public sah untuk KEDUA varian itu (public -> public, dan
 * protected -> public karena memperluas visibilitas), sedangkan override
 * Kotlin atas metode protected Java adalah titik rapuh kompilasi.
 * File ini TIDAK memakai anotasi @Nonnull supaya tidak bergantung pada
 * ada/tidaknya jsr305 di classpath transitif NewPipe.
 *
 * Best-effort: kegagalan HTTP apa pun dilempar ke pemanggil, dan pemanggil
 * (NewPipeStreamExtractor) mengubahnya menjadi hasil kosong — Innertube
 * tetap menjadi fallback, bukan crash.
 */
public class OkHttpNewPipeDownloader extends Downloader {

    private final OkHttpClient client;

    public OkHttpNewPipeDownloader(OkHttpClient client) {
        this.client = client;
    }

    @Override
    // v1.0.2-fix: Downloader.execute di NewPipeExtractor 0.26.4 TIDAK
    // mendeklarasikan ExtractionException (hanya IOException), jadi
    // deklarasi itu dihapus — kalau tidak, javac menolak override-nya.
    // Juga: OkHttp Response di Java diakses lewat method (), bukan
    // property gaya Kotlin (body/code/message/headers/request).
    public Response execute(Request request) throws IOException {
        byte[] data = request.dataToSend();
        RequestBody body = null;
        if (data != null) {
            body = RequestBody.create(data, null);
        }
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(request.url());
        Map<String, List<String>> headers = request.headers();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    builder.addHeader(entry.getKey(), value);
                }
            }
        }
        String method = request.httpMethod();
        if (body != null) {
            builder.method(method, body);
        } else if ("GET".equalsIgnoreCase(method)) {
            builder.get();
        } else {
            builder.method(method, null);
        }
        try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            return new Response(
                    response.code(),
                    response.message(),
                    response.headers().toMultimap(),
                    responseBody,
                    response.request().url().toString()
            );
        }
    }
}
