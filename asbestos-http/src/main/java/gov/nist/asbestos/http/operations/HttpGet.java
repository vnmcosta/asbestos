package gov.nist.asbestos.http.operations;


import gov.nist.asbestos.http.util.Gzip;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class HttpGet extends HttpBase {
    // TODO GET parameters in the body
    void get(URI uri, Map<String, String> headers) {
        this.uri = uri;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            if (_requestHeaders != null)
                addHeaders(connection, _requestHeaders);
            if (headers != null)
                addHeaders(connection, headers);
            requestHeadersList = connection.getRequestProperties();
            status = connection.getResponseCode();
//            try {
            InputStream is = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            setResponseHeadersList(connection.getHeaderFields());
            byte[] bytes = null;
            if (status >= 400) {
                try {
                    bytes = IOUtils.toByteArray(is);
                } catch (Throwable t) {
                    // ignore
                }
            } else {
                bytes = IOUtils.toByteArray(is);
            }
            if (isResponseGzipEncoded())
                setResponse(Gzip.decompressGZIP(bytes));
            else
                setResponse(bytes);
//            } catch (Throwable t) {
            // ok - won't always be available
            //System.out.println(t.getMessage());
//            }
        } catch (Throwable t) {
            throw new Error("GET " + uri + "\n" + t.getMessage(), t);
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    public boolean isSearch() {
        String query = uri.getQuery();
        return query != null && !query.equals("");
    }

    public void get(String url) throws URISyntaxException {
        get(new URI(url), (Map<String, String>) null);
    }

    public void get() {
        try {
            get(getUri(), getRequestHeaders().getAll());
        } catch (Throwable e) {
            status = 404;
            String msg = e.getMessage() + "\n" + ExceptionUtils.getStackTrace(e);
            setResponseText(msg);
            setResponse(msg.getBytes());
        }
    }

    public HttpGet get(URI uri, String contentType)  {
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", contentType);
        headers.put("accept-charset", "utf-8");
        get(uri, headers);
        return this;
    }

    public HttpGet getJson(String url) throws URISyntaxException {
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "application/json");
        headers.put("accept-charset", "utf-8");
        get(new URI(url), headers);
        if (getResponse() != null)
            setResponseText(new String(getResponse()));
        return this;
    }

    public HttpGet getJson(URI uri)  {
        get(uri, "application/json");
        return this;
    }

    public HttpGet run()  {
        Objects.requireNonNull(uri);
        get(uri, getRequestHeaders().getAll());
        return this;
    }

    public String getVerb() {
        return "GET";
    }

}
