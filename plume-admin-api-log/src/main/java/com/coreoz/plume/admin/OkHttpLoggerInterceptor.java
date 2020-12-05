package com.coreoz.plume.admin;

import com.coreoz.plume.admin.services.logApi.HttpHeader;
import com.coreoz.plume.admin.services.logApi.LogApiService;
import com.coreoz.plume.admin.services.logApi.LogInterceptApiBean;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import okhttp3.Connection;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.http.HttpHeaders;
import okio.Buffer;
import okio.BufferedSource;
import okio.GzipSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

public class OkHttpLoggerInterceptor implements Interceptor {

    private static final Logger logger = LoggerFactory.getLogger("api.http");
    private static final BiPredicate<Request, Response> ALWAYS_TRUE_BI_PREDICATE = (request, response) -> true;

    private final String apiName;
    private final LogApiService logApiService;
    private final BiPredicate<Request, Response> okHttpLoggerFiltersFunction;

    public OkHttpLoggerInterceptor(String apiName, LogApiService logApiService, BiPredicate<Request, Response> okHttpLoggerFiltersFunction) {
        this.apiName = apiName;
        this.logApiService = logApiService;
        this.okHttpLoggerFiltersFunction = okHttpLoggerFiltersFunction;
    }

    public OkHttpLoggerInterceptor(String apiName, LogApiService logApiService) {
        this(apiName, logApiService, ALWAYS_TRUE_BI_PREDICATE);
    }

    @NotNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        request.url().encodedPath();

        List<HttpHeader> requestHeaders = new ArrayList<>();
        List<HttpHeader> responseHeaders = new ArrayList<>();
        RequestBody requestBody = request.body();
        boolean hasRequestBody = requestBody != null;
        String requestBodyString = null;
        if (hasRequestBody) {
            Buffer bufferRq = new Buffer();
            requestBody.writeTo(bufferRq);
            requestBodyString = bufferRq.clone().readString(StandardCharsets.UTF_8);
        }
        Connection connection = chain.connection();
        String requestStartMessage = "--> " + request.method() + ' ' + request.url() + (connection != null ? " " + connection.protocol() : "");
        if (hasRequestBody) {
            requestStartMessage = requestStartMessage + " (" + requestBody.contentLength() + "-byte body)";
        }
        logger.debug(requestStartMessage);
        if (hasRequestBody) {
            requestHeaders = this.handleRequestBodyAngGetHeaders(request);
        } else {
            logger.debug("--> END {}", request.method());
        }

        long startNs = System.nanoTime();

        Response response = this.executeRequestAndGetResponse(chain, request, requestBodyString, requestHeaders);

        if (this.okHttpLoggerFiltersFunction.test(request, response)) {
            logger.info("Ok Http logger filtered");
            return response;
        }

        Buffer bufferRs = null;
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        ResponseBody responseBody = response.body();

        long contentLength = responseBody.contentLength();
        String bodySize = contentLength != -1L ? contentLength + "-byte" : "unknown-length";
        String responseMessage = response.message().isEmpty() ? "-- no message --" : response.message();
        logger.debug("<-- {} {} {} ({} ms, {} body)",
            response.code(),
            responseMessage,
            response.request().url(),
            tookMs,
            bodySize
        );

        Headers headers = response.headers();
        int i = 0;

        for (int count = headers.size(); i < count; ++i) {
            responseHeaders.add(new HttpHeader(headers.name(i), headers.value(i)));
            logger.debug("{}: {}", headers.name(i), headers.value(i));
        }

        if (HttpHeaders.hasBody(response)) {
            if (this.bodyHasUnknownEncoding(response.headers())) {
                logger.debug("<-- END HTTP (encoded body omitted)");
            } else {
                BufferedSource source = responseBody.source();
                source.request(9223372036854775807L);
                Buffer buffer = source.getBuffer();
                bufferRs = source.getBuffer();
                Long gzippedLength = null;
                if ("gzip".equalsIgnoreCase(headers.get("Content-Encoding"))) {
                    gzippedLength = buffer.size();
                    GzipSource gzippedResponseBody = null;

                    try {
                        gzippedResponseBody = new GzipSource(buffer.clone());
                        buffer = new Buffer();
                        buffer.writeAll(gzippedResponseBody);
                        bufferRs.writeAll(gzippedResponseBody);
                    } finally {
                        if (gzippedResponseBody != null) {
                            gzippedResponseBody.close();
                        }
                    }
                }

                Charset charset = StandardCharsets.UTF_8;
                MediaType contentType = responseBody.contentType();
                if (contentType != null) {
                    charset = contentType.charset(StandardCharsets.UTF_8);
                }

                if (!isPlaintext(buffer)) {
                    logger.debug("");
                    logger.debug("<-- END HTTP (binary {}-byte body omitted)", buffer.size());
                    return response;
                }

                if (contentLength != 0L) {
                    logger.debug("");
                    logger.debug(buffer.clone().readString(charset));
                }

                if (gzippedLength != null) {
                    logger.debug("<-- END HTTP ({}-byte, {}-gzipped-byte body)", buffer.size(), gzippedLength);
                } else {
                    logger.debug("<-- END HTTP ({}-byte body)", buffer.size());
                }
            }
        } else {
            logger.debug("<-- END HTTP");
        }

        LogInterceptApiBean logInterceptApiBean = new LogInterceptApiBean(
            request.url().toString(),
            request.method(),
            response.code(),
            requestBodyString,
            bufferRs == null ? null : bufferRs.clone().readString(StandardCharsets.UTF_8),
            requestHeaders,
            responseHeaders,
            this.apiName
        );
        logApiService.saveLog(logInterceptApiBean);
        return response;
    }

    static boolean isPlaintext(Buffer buffer) {
        try {
            Buffer prefix = new Buffer();
            long byteCount = Math.min(buffer.size(), 64L);
            buffer.copyTo(prefix, 0L, byteCount);

            for (int i = 0; i < 16 && !prefix.exhausted(); ++i) {
                int codePoint = prefix.readUtf8CodePoint();
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                    return false;
                }
            }

            return true;
        } catch (EOFException var6) {
            return false;
        }
    }

    private List<HttpHeader> handleRequestBodyAngGetHeaders(Request request) throws IOException {
        List<HttpHeader> requestHeaders = new ArrayList<>();
        RequestBody requestBody = request.body();
        if (requestBody.contentType() != null) {
            requestHeaders.add(new HttpHeader("Content-Type", requestBody.contentType().toString()));
            logger.debug("Content-Type: {}", requestBody.contentType());
        }

        if (requestBody.contentLength() != -1L) {
            logger.debug("Content-Length: {}", requestBody.contentLength());
        }

        Headers headers = request.headers();
        int i = 0;

        for (int count = headers.size(); i < count; ++i) {
            String name = headers.name(i);
            if (!"Content-Type".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name)) {
                requestHeaders.add(new HttpHeader(headers.name(i), headers.value(i)));
                logger.debug("{}: {}", name, headers.value(i));
            }
        }
        if (this.bodyHasUnknownEncoding(request.headers())) {
            logger.debug("--> END {} (encoded body omitted)", request.method());
        } else {
            Buffer buffer = new Buffer();
            requestBody.writeTo(buffer);
            Charset charset = StandardCharsets.UTF_8;
            MediaType contentType = requestBody.contentType();
            if (contentType != null) {
                charset = contentType.charset(StandardCharsets.UTF_8);
            }

            logger.debug("");
            if (isPlaintext(buffer)) {
                logger.debug(buffer.readString(charset));
                logger.debug("--> END {} ({}-byte body)", request.method(), requestBody.contentLength());
            } else {
                logger.debug("--> END {} (binary {}-byte body omitted)", request.method(), requestBody.contentLength());
            }
        }
        return requestHeaders;
    }

    private Response executeRequestAndGetResponse(
        Chain chain,
        Request request,
        String requestBodyString,
        List<HttpHeader> requestHeaders
    ) throws IOException {
        try {
            return chain.proceed(request);
        } catch (Exception e) {
            logger.debug("<-- HTTP FAILED: ", e);

            LogInterceptApiBean logInterceptApiBean = new LogInterceptApiBean(
                request.url().toString(),
                request.method(),
                500,
                requestBodyString,
                Throwables.getStackTraceAsString(e),
                requestHeaders,
                ImmutableList.of(),
                this.apiName
            );
            logApiService.saveLog(logInterceptApiBean);

            throw e;
        }
    }

    private boolean bodyHasUnknownEncoding(Headers headers) {
        String contentEncoding = headers.get("Content-Encoding");
        return contentEncoding != null && !contentEncoding.equalsIgnoreCase("identity") && !contentEncoding.equalsIgnoreCase("gzip");
    }

}
