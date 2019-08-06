package gov.nist.asbestos.asbestosProxy.wrapper;


import com.fasterxml.jackson.databind.ObjectMapper;
import gov.nist.asbestos.asbestosProxy.channel.*;
import gov.nist.asbestos.client.events.Event;
import gov.nist.asbestos.client.log.SimStore;
import gov.nist.asbestos.client.events.Task;
import gov.nist.asbestos.asbestosProxy.util.Gzip;
import gov.nist.asbestos.client.Base.ProxyBase;
import gov.nist.asbestos.client.client.Format;
import gov.nist.asbestos.client.resolver.Ref;
import gov.nist.asbestos.http.headers.Header;
import gov.nist.asbestos.http.headers.Headers;
import gov.nist.asbestos.http.operations.*;
import gov.nist.asbestos.sharedObjects.ChannelConfig;
import gov.nist.asbestos.sharedObjects.ChannelConfigFactory;
import gov.nist.asbestos.simapi.simCommon.SimId;
import gov.nist.asbestos.simapi.simCommon.TestSession;
import gov.nist.asbestos.simapi.tk.installation.Installation;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.IntStream;

public class ProxyServlet extends HttpServlet {
    private static Logger log = Logger.getLogger(ProxyServlet.class);
    private File externalCache = null;

    private Map<String, IChannelBuilder> proxyMap = new HashMap<>();

    public ProxyServlet() {
        super();
        proxyMap.put("passthrough", new PassthroughChannelBuilder());
        proxyMap.put("mhd", new MhdChannelBuilder());
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        // TODO put EC location in web.xml
        setExternalCache(new File("/home/bill/ec"));
    }

    private static URI buildURI(HttpServletRequest req) {
        Map<String, List<String>> parms = req.getParameterMap();
        parms = fixParmMap(parms);
        return HttpBase.buildURI(req.getRequestURI(), parms);
    }

    private static Map<String, List<String>> fixParmMap(Map<String, List<String>> parms) {
        Map<String, List<String>> map = new HashMap<>();
        for (String key : parms.keySet()) {
            if (key.contains("=")) {
                String[] parts = key.split("=");
                String theKey = parts[0];
                String[] values = parts[1].split(",");
                List<String> theValues = new ArrayList<>(Arrays.asList(values));
                map.put(theKey, theValues);
            } else {
                map.put(key, parms.get(key));
            }
        }
        return map;
    }

    public static String[] addEventHeader(HttpServletResponse resp, String hostport, Task task) {
        Header header = buildEventHeader(hostport, task);
        resp.addHeader(header.getName(), header.getValue());
        String[] returned = new String[2];
        returned[0] = header.getName();
        returned[1] = header.getValue();
        return returned;
    }


    public static String[] addEventHeader(HttpBase resp, String hostport, Task task) {
        Header header = buildEventHeader(hostport, task);
        resp.getResponseHeaders().add(header);
        String[] returned = new String[2];
        returned[0] = header.getName();
        returned[1] = header.getValue();
        return returned;
    }

    private static Header buildEventHeader(String hostport, Task task) {
        if (hostport == null) return null;
        if (task == null) return null;
        File eventDir = task.getEvent().getEventDir();
        String[] parts = eventDir.toString().split("/");
        int length = parts.length;
        if (length < 6) return null;
        String event = parts[length-1];
        String resource = parts[length-2];
        String testsession = parts[length-4];
        String environment = parts[length-5];

        String uri = "http://" +
                hostport +
                "/proxy/log/" +
                environment + "/" +
                testsession + "/" +
                resource + "/" +
                event;
        return new Header("x-proxy-event", uri);
    }

    private static String getHostPort(Headers inHeaders) throws ServletException {
        String hostport = inHeaders.getValue("host");
        if (hostport == null || !hostport.contains(":"))
            throw new ServletException("host header missing or not formatted as host:port");
        return hostport;
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        Event event = null;
        String hostport = null;

        Task clientTask = null;

        // typical URI is
        // for FHIR translation
        // http://host:port/appContext/prox/simId/actor/transaction
        // for general stuff
        // http://host:port/appContext/prox/simId
//        resp.sendError(resp.SC_BAD_GATEWAY,'done')
        try {
            URI uri = buildURI(req);
            //String uri = req.requestURI
            log.debug("doPost " + uri);
            SimStore simStore = parseUri(uri, req, resp, Verb.POST);
            if (simStore == null)
                return;

            if (!simStore.isChannel())
                throw new ServletException("Proxy - POST of configuration data not allowed on " + uri);

            // these should be redundant given what is done in parseUri()
            String channelType = simStore.getChannelConfig().getChannelType();
            if (channelType == null)
                throw new ServletException("Sim " + simStore.getChannelId() + " does not define a Channel Type.");
            IChannelBuilder channelBuilder = proxyMap.get(channelType);
            BaseChannel channel = channelBuilder.build();

            channel.setup(simStore.getChannelConfig());

            Headers inHeaders = getRequestHeaders(req, Verb.POST);
            channel.setReturnFormatType(Format.resultContentType(inHeaders));
            hostport = inHeaders.getValue("host");
            if (hostport == null || !hostport.contains(":"))
                throw new ServletException("host header missing or not formatted as host:port");

            byte[] inBody = getRequestBody(req);

            // HttpPost requestIn = new HttpPost();

            event = simStore.newEvent();
            clientTask = event.getClientTask();
            HttpPost requestIn = (HttpPost) logClientRequestIn(clientTask, inHeaders, inBody, Verb.POST);

            log.info("=> " + simStore.getEndpoint() + " " + clientTask.getRequestHeader().getContentType());

            // interaction between proxy and target service
            Task backSideTask = clientTask.newTask();

            String proxyBase = new Ref(uri).getBase().withHostPort(hostport).toString();
            String fhirBase = new Ref(requestIn.getRequestHeaders().getPathInfo()).getBase().toString();
            channel.setProxyBase(proxyBase);
            channel.setServerBase(fhirBase);

            URI outURI = transformRequestUri(backSideTask, requestIn, channel);
            // transform input request for backend service
            HttpBase requestOut;
            try {
                requestOut = transformRequest(backSideTask, requestIn, outURI, channel);
            } catch (TransformException te) {
                returnTransformException(resp, clientTask, te);
                addEventHeader(resp, hostport, clientTask);
                return;
            } catch (Throwable t) {
                log.error(ExceptionUtils.getStackTrace(t));
                returnOperationOutcome(req, resp, clientTask, t);
                addEventHeader(resp, hostport, clientTask);
                return;
            }
            requestOut.setUri(outURI);
//            requestOut.getRequestHeaders().setPathInfo(outUri);
//            requestOut.setRequest(requestIn.getRequest());

            // send request to backend service
            requestOut.run();

            // log response from backend service
            logResponse(backSideTask, requestOut);

            // transform backend service response for client
            HttpBase responseOut;
            try {
                responseOut = transformResponse(clientTask, requestOut, channel, hostport);
            } catch (TransformException te) {
                returnTransformException(resp, clientTask, te);
                addEventHeader(resp, hostport, clientTask);
                return;
            }

            if (responseOut.getResponseHeaders() != null)
                responseOut.getResponseHeaders().getAll().forEach(resp::addHeader);

            if (responseOut.getResponse() != null) {
                resp.getOutputStream().write(responseOut.getResponse());
            }
            addEventHeader(resp, hostport, clientTask);

            log.info("OK");

        } catch (Throwable t) {
            log.error(ExceptionUtils.getStackTrace(t));
            resp.setStatus(resp.SC_INTERNAL_SERVER_ERROR);
            if (clientTask != null) {
                returnOperationOutcome(req, resp, clientTask, t);
                addEventHeader(resp, hostport, clientTask);
            }
        }
    }

    private void returnTransformException(HttpServletResponse resp, Task task, TransformException te) throws IOException {
        Format format = te.getFormat();
        String body = te.getResponse();
        byte[] bodyBytes = body.getBytes();
        Headers headers = new Headers();
        headers.setStatus(200);
        headers.getHeaders().add(new Header("Content-Type", format.getContentType()));

        task.putResponseBodyText(body);
        task.putResponseHeader(headers);

        resp.addHeader("Content-Type", format.getContentType());
        resp.getOutputStream().write(bodyBytes);
        log.error(body);
    }

    @Override
    public void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        Event event = null;
        try {
            URI uri = buildURI(req);
            log.info("doDelete  " + uri);
            SimStore simStore = parseUri(uri, req, resp, Verb.DELETE);
            if (simStore == null) {
                resp.setStatus(resp.SC_OK);
                log.info("channel deleted");
                return;
            }
            event = simStore.newEvent();
            doGetDelete(req, resp, uri, simStore, event, Verb.DELETE);
        } catch (Throwable t) {
            log.error(ExceptionUtils.getStackTrace(t));
            resp.setStatus(resp.SC_INTERNAL_SERVER_ERROR);
            returnOperationOutcome(req, resp, event.getClientTask(), t);
        }
    }

    private void returnOperationOutcome(HttpServletRequest req, HttpServletResponse resp, Task task, Throwable t) throws IOException, ServletException {
        Headers headers = getRequestHeaders(req, Verb.GET);  // verb not used
        Header acceptHeader = headers.getAccept();
        Headers responseHeaders = new Headers();

        OperationOutcome oo = outcomeFromException(t);
        if (acceptHeader != null && acceptHeader.getValue().contains("json")) {
            String ooString = ProxyBase.encode(oo, Format.JSON);
            task.putResponseBodyText(ooString);
            resp.getWriter().print(ooString);
            resp.addHeader("Content-Type", Format.JSON.getContentType());
            responseHeaders.add(new Header("Content-Type", Format.JSON.getContentType()));
        } else {
            String ooString = ProxyBase.encode(oo, Format.XML);
            task.putResponseBodyText(ooString);
            resp.getWriter().print(ooString);
            resp.addHeader("Content-Type", Format.XML.getContentType());
            responseHeaders.add(new Header("Content-Type", Format.XML.getContentType()));
        }
        Headers inHeaders = getRequestHeaders(req, Verb.POST);
        String hostport = getHostPort(inHeaders);
        String[] eventHeader = addEventHeader(resp, hostport, task);
        if (eventHeader != null)
            responseHeaders.add(new Header(eventHeader[0], eventHeader[1]));
        task.putResponseHeader(responseHeaders);
    }

    private OperationOutcome outcomeFromException(Throwable t) {
        OperationOutcome oo = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent issue = oo.addIssue();
        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        issue.setCode(OperationOutcome.IssueType.EXCEPTION);
        issue.setDiagnostics(ExceptionUtils.getStackTrace(t));
        return oo;
    }

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        Event event = null;
        try {
            URI uri = buildURI(req);
            log.info("doGet " + uri);
            SimStore simStore = parseUri(uri, req, resp, Verb.GET);
            if (simStore == null)
                return;

            event = simStore.newEvent();
            doGetDelete(req, resp, uri, simStore, event, Verb.GET);

        } catch (Throwable t) {
            log.error(ExceptionUtils.getStackTrace(t));
            if (new Ref(buildURI(req)).isQuery()) {
                OperationOutcome oo = outcomeFromException(t);
                Bundle bundle = new Bundle();
                Bundle.BundleEntryComponent bundleEntryComponent = bundle.addEntry();
                Bundle.BundleEntrySearchComponent search = bundleEntryComponent.getSearch();
                search.setMode(Bundle.SearchEntryMode.OUTCOME);
                Bundle.BundleEntryResponseComponent resp1 = bundleEntryComponent.getResponse();
                resp1.setOutcome(oo);

                Headers inHeaders = getRequestHeaders(req, Verb.GET);
                String content = ProxyBase.encode(bundle, Format.resultContentType(inHeaders));
                sendResponse(resp, content.getBytes(), Format.resultContentType(inHeaders), getHostPort(inHeaders), event.getClientTask());
            } else {
                returnOperationOutcome(req, resp, event.getClientTask(), t);
            }
        }
    }

    private void doGetDelete(HttpServletRequest req, HttpServletResponse resp, URI uri, SimStore simStore, Event event, Verb verb) throws Exception {
        Task clientTask = event.getClientTask();
        String channelType = simStore.getChannelConfig().getChannelType();
        if (channelType == null)
            throw new Exception("Sim " + simStore.getChannelId() + " does not define a Channel Type.");
        IChannelBuilder channelBuilder = proxyMap.get(channelType);
        BaseChannel channel = channelBuilder.build();

        channel.setup(simStore.getChannelConfig());
        Headers inHeaders = getRequestHeaders(req, verb);
        channel.setReturnFormatType(Format.resultContentType(inHeaders));
        channel.setHostport(getHostPort(inHeaders));
        channel.setTask(clientTask);

        // handle non-channel requests
        if (!simStore.isChannel()) {
            Map<String, List<String>> parameters = req.getParameterMap();
            String result = controlRequest(simStore, uri, parameters);
            resp.getOutputStream().print(result);
            return;
        }

        byte[] inBody = getRequestBody(req);
        String hostport = inHeaders.getValue("host");

        try {
            HttpBase requestIn = logClientRequestIn(clientTask, inHeaders, inBody, verb);

            log.info("=> " + simStore.getEndpoint() + " " + clientTask.getRequestHeader().getAccept());

            Task backSideTask = clientTask.newTask();


            URI outURI = transformRequestUri(backSideTask, requestIn, channel);
            // transform input request for backend service
            HttpBase requestOut;
            if (requestIn instanceof HttpGet)
                requestOut = transformRequest(backSideTask, (HttpGet) requestIn, outURI, channel);
            else if (requestIn instanceof HttpPost)
                requestOut = transformRequest(backSideTask, (HttpPost) requestIn, outURI, channel);
            else
                requestOut = transformRequest(backSideTask, (HttpDelete) requestIn, outURI, channel);


            // send request to backend service
            requestOut.run();

            // log response from backend service
            logResponse(backSideTask, requestOut);

            // transform backend service response for client
            HttpBase responseOut = transformResponse(backSideTask, requestOut, channel, hostport);
            logResponse(clientTask, responseOut);

            for (Header header : responseOut.getResponseHeaders().getHeaders()) {
                resp.addHeader(header.getName(), header.getAllValuesAsString());
            }
            byte[] response = responseOut.getResponse();
            if (response != null) {
                resp.getOutputStream().write(response);
            }
            log.info("OK");

        } finally {
            addEventHeader(resp, hostport, clientTask);
        }
    }

    private static void sendResponse(HttpServletResponse resp, byte[] content, Format format, String hostport, Task clientTask) {
        try {
            resp.getOutputStream().write(content);
            resp.addHeader("Content-Type", format.getContentType());
        } catch (Exception e) {
        } finally {
            addEventHeader(resp, hostport, clientTask);
        }
    }

    private static void logResponse(Task task, HttpBase requestOut) {
        // log response from backend service
        Headers responseHeaders = requestOut.getResponseHeaders();
        responseHeaders.setStatus(requestOut.getStatus());
        responseHeaders.setVerb(requestOut.getVerb());
        responseHeaders.setPathInfo(requestOut.getUri());
        task.putResponseHeader(responseHeaders);
        // TODO make this next line not seem to work
        //task.event._responseHeaders = requestOut._responseHeaders
        logResponseBody(task, requestOut);
        log.info("==> " + requestOut.getStatus() + " " + ((requestOut.getResponse() != null) ? requestOut.getResponseContentType() + " " + requestOut.getResponse().length + " bytes" : "NULL"));
    }

    private static Headers getRequestHeaders(HttpServletRequest req, Verb verb) {
        List<String> names = Collections.list(req.getHeaderNames());
        Map<String, List<String>> hdrs = new HashMap<>();
        for (String name : names) {
            List<String> values = Collections.list(req.getHeaders(name));
            hdrs.put(name, values);
        }
        Headers headers = new Headers(hdrs);
        headers.setVerb(verb.toString());
        try {
            headers.setPathInfo(buildURI(req));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return headers;
    }

    static HttpBase logClientRequestIn(Task task, Headers headers, byte[] body, Verb verb) {
        HttpBase base = (verb == Verb.GET) ? new HttpGet() : (verb == Verb.DELETE ? new HttpDelete() : new HttpPost());
        task.putRequestHeader(headers);
        base.setRequestHeaders(headers);

        task.putRequestBody(body);
        base.setRequest(body);
        String encoding = (headers.getContentEncoding().getAllValues().isEmpty()) ? "" : headers.getContentEncoding().getAllValues().get(0);
        if (encoding.equalsIgnoreCase("gzip")) {
            String txt = Gzip.decompressGZIP(body);
            task.putRequestBodyText(txt);
            base.setRequestText(txt);
        } else if (headers.getContentType().getAllValues().get(0).equalsIgnoreCase("text/html")) {
            task.putRequestHTMLBody(body);
            base.setRequestText(new String(body));
        } else if (isStringType(headers.getContentType().getAllValues().get(0))) {
            task.putRequestBodyText(new String(body));
            base.setRequestText(new String(body));
        } else {
            task.putRequestBodyText(new String(body));
            base.setRequestText(new String(body));
        }
        return base;
    }

    private static List<String> stringTypes = Arrays.asList(
            "application/fhir+json",
            "application/json+fhir",
            "application/soap+xml",
            "multipart/related"
    );

    static boolean isStringType(String type) {
        return type.startsWith("text") || stringTypes.contains(type);
    }

    static byte[] getRequestBody(HttpServletRequest req) {
        byte[] bytes;
        try {
            bytes = IOUtils.toByteArray(req.getInputStream());
        } catch (Exception e) {
            throw new  RuntimeException(e);
        }
        return bytes;
    }

    static void logRequestBody(Task task, Headers headers, HttpBase http, HttpServletRequest req) {
        byte[] bytes;
        try {
            bytes = IOUtils.toByteArray(req.getInputStream());
        } catch (Exception e) {
            throw new  RuntimeException(e);
        }
        task.putRequestBody(bytes);
        http.setRequest(bytes);
        String encoding = headers.getContentEncoding().getAllValues().get(0);
        if (encoding.equalsIgnoreCase("gzip")) {
            String txt = Gzip.decompressGZIP(bytes);
            task.putRequestBodyText(txt);
            http.setRequest(txt.getBytes());
        } else if (headers.getContentType().getAllValues().get(0).equalsIgnoreCase("text/html")) {
            task.putRequestHTMLBody(bytes);
            http.setRequestText(new String(bytes));
        } else if (isStringType(headers.getContentType().getAllValues().get(0))) {
            http.setRequestText(new String(bytes));
        }
    }

    static void logResponseBody(Task task, HttpBase http) {
        Headers headers = http.getResponseHeaders();
        byte[] bytes = http.getResponse();
        task.putResponseBody(bytes);
        if (headers == null) {
            String txt = new String(bytes);
            http.setResponseText(txt);
            task.putResponseBodyText(txt);
        } else {
            List<String> encodings = headers.getContentEncoding().getAllValues();
            if (encodings.isEmpty()) {
                String contentType = headers.getContentType().getAllValues().get(0);
                if (isStringType(contentType)) {
                    String txt = new String(bytes);
                    http.setResponseText(txt);
                    task.putResponseBodyText(txt);
                }
            } else {
                String encoding = encodings.get(0);
                if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
                    String txt = Gzip.decompressGZIP(bytes);
                    task.putResponseBodyText(txt);
                    http.setResponseText(txt);
                } else if (headers.getContentType().getAllValues().get(0).equalsIgnoreCase("text/html")) {
                    task.putResponseHTMLBody(bytes);
                    http.setResponseText(new String(bytes));
                } else if (isStringType(headers.getContentType().getAllValues().get(0))) {
                    http.setResponseText(new String(bytes));
                }
            }
        }
    }

    static void logBackendRequest(Task task, HttpBase http) {
        Headers headers = http.getRequestHeaders();
        task.putRequestHeader(headers);
        byte[] bytes = http.getRequest();
        task.putRequestBody(bytes);
        List<String> encodings = headers.getContentEncoding().getAllValues();
        if (encodings.isEmpty()) {
            String contentType = headers.getContentType().getAllValues().get(0);
            if (isStringType(contentType) || "".equals(contentType)) {
                String txt = new String(bytes);
                http.setRequestText(txt);
                task.putRequestBodyText(txt);
            }
        }
        else {
            String encoding = encodings.get(0);
            if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
                String txt = Gzip.decompressGZIP(bytes);
                task.putRequestBodyText(txt);
                http.setRequestText(txt);
            } else if (headers.getContentType().getAllValues().get(0).equalsIgnoreCase("text/html")) {
                task.putRequestHTMLBody(bytes);
                http.setRequestText(new String(bytes));
            } else if (isStringType(headers.getContentType().getAllValues().get(0))) {
                http.setRequestText(new String(bytes));
            }
        }
    }

    static HttpBase transformRequest(Task task, HttpPost requestIn, URI newURI, IBaseChannel channelTransform) {
        HttpPost requestOut = new HttpPost();
        channelTransform.setTask(task);
        channelTransform.transformRequest(requestIn, requestOut);

        requestOut.setUri(newURI);
        requestOut.getRequestHeaders().setPathInfo(requestOut.getUri());

        logBackendRequest(task, requestOut);

        return requestOut;
    }

    static HttpBase transformRequest(Task task, HttpGet requestIn, URI newURI, IBaseChannel channelTransform) {
        HttpGet requestOut = new HttpGet();

        channelTransform.transformRequest(requestIn, requestOut);

        requestOut.setUri(newURI);
        requestOut.getRequestHeaders().setPathInfo(requestOut.getUri());

        task.putRequestHeader(requestOut.getRequestHeaders());

        return requestOut;
    }

    static HttpBase transformRequest(Task task, HttpDelete requestIn, URI newURI, IBaseChannel channelTransform) {
        HttpDelete requestOut = new HttpDelete();

        channelTransform.transformRequest(requestIn, requestOut);

        requestOut.setUri(newURI);
        requestOut.getRequestHeaders().setPathInfo(requestOut.getUri());

        task.putRequestHeader(requestOut.getRequestHeaders());

        return requestOut;
    }

    static URI transformRequestUri(Task task, HttpBase requestIn, IBaseChannel channelTransform) {
        Headers headers = requestIn.getRequestHeaders();
        return channelTransform.transformRequestUrl(headers.getPathInfo().getPath(), requestIn);
    }

    static HttpBase transformResponse(Task task, HttpBase responseIn, IBaseChannel channelTransform, String proxyHostPort) {
        HttpBase responseOut = new HttpGet();  // here GET vs POST does not matter

        try {
            channelTransform.transformResponse(responseIn, responseOut, proxyHostPort);
        } catch (TransformException te) {
            if (responseOut.getResponseHeaders() != null)
                responseOut.getResponseHeaders().removeHeader("transfer-encoding");

            responseOut.setResponse(te.getResponse().getBytes());
            responseOut.setRequestHeaders(new Headers().withContentType(te.getFormat().getContentType()));
        } finally {
            if (responseOut.getResponseHeaders() != null)
                responseOut.getResponseHeaders().removeHeader("transfer-encoding");

            if (responseOut.getResponse() != null) {
                task.putResponseBody(responseOut.getResponse());
                task.putResponseHeader(responseOut.getResponseHeaders());
                logResponseBody(task, responseOut);
            }
        }
        responseOut.setStatus(responseIn.getStatus());
        return responseOut;
    }

    SimStore parseUri(URI uri, HttpServletRequest req, HttpServletResponse resp, Verb verb) throws IOException {
        List<String> uriParts1 = Arrays.asList(uri.getPath().split("/"));
        List<String> uriParts = new ArrayList<>(uriParts1);  // so parts are deletable
        SimStore simStore;

        if (uriParts.size() == 3 && uriParts.get(2).equals("prox") && verb != Verb.DELETE) {
            // CREATE
            // /appContext/prox
            // control channel - request to create proxy channel
            // can be done with GET or POST

            String parmameterString = uri.getQuery();

            if (verb == Verb.POST) {
                String rawRequest = IOUtils.toString(req.getInputStream(), Charset.defaultCharset());   // json
                log.debug("CREATESIM " + rawRequest);
                ChannelConfig channelConfig = ChannelConfigFactory.convert(rawRequest);
                simStore = new SimStore(externalCache,
                        new SimId(new TestSession(channelConfig.getTestSession()),
                                channelConfig.getChannelId(),
                                channelConfig.getActorType(),
                                channelConfig.getEnvironment(),
                                true));

                simStore.create(channelConfig);
                log.info("Channel " + simStore.getChannelId().toString() + " created (type " + simStore.getActorType() + ")" );

                resp.setContentType("application/json");
                resp.getOutputStream().print(rawRequest);


                resp.setStatus((simStore.isNewlyCreated() ? resp.SC_CREATED : resp.SC_OK));
                log.info("OK");
                return null;  // trigger - we are done - exit now
            } else  if (parmameterString != null) {  // GET with parameters - also CREATE SIM
                Map<String, List<String>> queryMap = HttpBase.mapFromQuery(parmameterString);
                String json = new ObjectMapper().writeValueAsString(HttpBase.flattenQueryMap(queryMap));
                ChannelConfig channelConfig = ChannelConfigFactory.convert(json);
                SimId simId = new SimId(new TestSession(channelConfig.getTestSession()), channelConfig.getChannelId());
                simStore = new SimStore(externalCache, simId);

                resp.setContentType("application/json");
                resp.getOutputStream().print(json);


                resp.setStatus((simStore.isNewlyCreated() ? resp.SC_CREATED : resp.SC_OK));
                log.info("OK");
                return null;
            }
        }

        SimId simId = null;

        if (uriParts.size() >= 4) {
            // /appContext/prox/channelId
            if (uriParts.get(0).equals("") && uriParts.get(2).equals("prox")) { // no appContext
                simId = SimId.buildFromRawId(uriParts.get(3));
                simStore = new SimStore(externalCache, simId);
                if (!simStore.exists()) {
                    resp.setStatus(resp.SC_NOT_FOUND);
                    return null;
                }
                simStore.open();

                uriParts.remove(0);  // leading empty string
                uriParts.remove(0);  // appContext
                uriParts.remove(0);  // prox
                uriParts.remove(0);  // channelId

                if (!simStore.exists()) {
                    if (verb == Verb.DELETE) {
                        resp.setStatus(resp.SC_OK);
                    } else {
                        resp.setStatus(resp.SC_NOT_FOUND);
                    }
                    return null;
                }
                if (uriParts.isEmpty() && verb == Verb.GET) {
                    // return channel config
                    String json = ChannelConfigFactory.convert(simStore.getChannelConfig());
                    resp.setContentType("application/json");
                    resp.getOutputStream().print(json);
                    return null;
                }
            }
            else
                return null;
        }  else
            return null;

        simStore = new SimStore(externalCache, simId);

        if (verb == Verb.DELETE) {
            Ref ref = new Ref(uri);
            if (!ref.hasResource()) {
                simStore.deleteSim();
                return null;  // delete channel
            }
        }

        //
        // everything above this is handling control operations
        // starting with this load of simStore, normal channel operations begin
        //


        // ChannelId has been established - from now all errors result in TaskStore logging

        // the request targets a Channel - maybe a control message or a pass through.
        // pass through have Channel/ as the next element of the URI


        if (!uriParts.isEmpty()) {
            simStore.setChannel(uriParts.get(0).equals("Channel"));   // Channel -> message passes through to backend system
            uriParts.remove(0);
        }

        if (!uriParts.isEmpty()) {
            simStore.setResource(uriParts.get(0));
            uriParts.remove(0);
        } else {
            // this only happens with transaction or batch
            simStore.setResource("Bundle");
        }

        // verify that proxy exists - only if this is a channel to a backend system
        if (simStore.isChannel())
            simStore.getStore();  // exception if proxy does not exist
        simStore.open();

        log.debug("Sim " + simStore.getChannelId() + " " +  simStore.getActorType() + " " + simStore.getResource());

        return simStore; // expect content

    }

    // /appContext/prox/channelId/?
    static String controlRequest(SimStore simStore, URI uri, Map<String, List<String>> parameters) {
        List<String> uriParts = Arrays.asList(uri.getPath().split("/"));
        if (uriParts.size() <= 4)
            throw new RuntimeException("Proxy control request - do not understand URI " + uri);
        IntStream.rangeClosed(1, 4)
                .forEach(x -> uriParts.remove(0));

        String type = uriParts.get(0);
        uriParts.remove(0);

        if (type.equals("TaskStore")) {
            return EventRequestHandler.eventRequest(simStore, uriParts, parameters);
        }
        throw new RuntimeException("Proxy: Do not understand control request type " + type + " of " + uri);
    }

    public void setExternalCache(File externalCache) {
        this.externalCache = externalCache;
        Installation.instance().setExternalCache(externalCache);
        log.debug("Asbestos Proxy init EC is " + externalCache.getPath());
    }
}
