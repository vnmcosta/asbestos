package gov.nist.asbestos.asbestosProxy.wrapper;


import com.fasterxml.jackson.databind.ObjectMapper;
import gov.nist.asbestos.asbestosProxy.channel.IBaseChannel;
import gov.nist.asbestos.asbestosProxy.channels.passthrough.PassthroughChannel;
import gov.nist.asbestos.asbestosProxy.events.Event;
import gov.nist.asbestos.asbestosProxy.log.SimStore;
import gov.nist.asbestos.asbestosProxy.log.Task;
import gov.nist.asbestos.asbestosProxy.util.Gzip;
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

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.IntStream;

public class ProxyServlet extends HttpServlet {
    private static Logger log = Logger.getLogger(ProxyServlet.class);
    private File externalCache = null;

    private Map<String, IBaseChannel> proxyMap = new HashMap<>();

    public ProxyServlet() {
        super();
        proxyMap.put("passthrough", new PassthroughChannel());
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        // TODO put EC location in web.xml
        setExternalCache(new File("/home/bill/ec"));
    }

    private static URI buildURI(HttpServletRequest req) {
        return HttpBase.buildURI(req.getRequestURI(), req.getParameterMap());
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) {

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
            IBaseChannel channel = (IBaseChannel) proxyMap.get(channelType);
            if (channel == null)
                throw new ServletException("Cannot create Channel of type " + channelType);

            channel.setup(simStore.getChannelConfig());

            Headers inHeaders = getRequestHeaders(req, Verb.POST);
            byte[] inBody = getRequestBody(req);

            // HttpPost requestIn = new HttpPost();

            Event event = simStore.newEvent();
            HttpPost requestIn = (HttpPost) logClientRequestIn(event, inHeaders, inBody, Verb.POST);

            log.info("=> " + simStore.getEndpoint() + " " +  event.getStore().getRequestHeader().getContentType());

            // interaction between proxy and target service
            Task backSideTask = event.getStore().newTask();

            // transform input request for backend service
            HttpBase requestOut = transformRequest(backSideTask, requestIn, channel);
            URI outUri = transformRequestUri(backSideTask, requestIn, channel);
            requestOut.setUri(outUri);
            requestOut.getRequestHeaders().setPathInfo(outUri);
            requestOut.setRequest(requestIn.getRequest());

            // send request to backend service
            requestOut.run();

            // log response from backend service
            logResponse(backSideTask, requestOut);

            // transform backend service response for client
            event.getStore().selectClientTask();
            HttpBase responseOut = transformResponse(event.getStore().selectTask(Task.CLIENT_TASK), requestOut, channel);

            responseOut.getResponseHeaders().getAll().forEach(resp::addHeader);

            if (responseOut.getResponse() != null) {
                resp.getOutputStream().write(responseOut.getResponse());
            }

            log.info("OK");

        } catch (Throwable t) {
            log.error(ExceptionUtils.getStackTrace(t));
            resp.setStatus(resp.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void doDelete(HttpServletRequest req, HttpServletResponse resp) {
        try {
            URI uri = buildURI(req);
            log.info("doDelete  " + uri);
            parseUri(uri, req, resp, Verb.DELETE);
            resp.setStatus(resp.SC_OK);
            log.info("OK");
        } catch (Throwable t) {
            log.error(ExceptionUtils.getStackTrace(t));
            resp.setStatus(resp.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) {
        try {
            URI uri = buildURI(req);
            log.info("doGet " + uri);
            SimStore simStore = parseUri(uri, req, resp, Verb.GET);
            if (simStore == null)
                return;

            String channelType = simStore.getChannelConfig().getChannelType();
            if (channelType == null)
                throw new Exception("Sim " + simStore.getChannelId() + " does not define a Channel Type.");
            IBaseChannel channel = (IBaseChannel) proxyMap.get(channelType);
            if (channel == null)
                throw new Exception("Cannot create Channel of type " + channelType);

            channel.setup(simStore.getChannelConfig());

            // handle non-channel requests
            if (!simStore.isChannel()) {
                Map<String, List<String>> parameters = req.getParameterMap();
                String result = controlRequest(simStore, uri,parameters);
                resp.getOutputStream().print(result);
                return;
            }

            Headers inHeaders = getRequestHeaders(req, Verb.GET);
            byte[] inBody = getRequestBody(req);


            Event event = simStore.newEvent();
            HttpGet requestIn = (HttpGet) logClientRequestIn(event, inHeaders, inBody, Verb.GET);

            Task backSideTask = event.getStore().newTask();

            log.info("=> " + simStore.getEndpoint() + " " + event.getStore().getRequestHeader().getAccept());

            // transform input request for backend service
            HttpBase requestOut = transformRequest(backSideTask, requestIn, channel);
            requestOut.setUri(transformRequestUri(backSideTask, requestIn, channel));
            requestOut.getRequestHeaders().setPathInfo(requestIn.getUri());

            // send request to backend service
            requestOut.run();

            // log response from backend service
            logResponse(backSideTask, requestOut);

            // transform backend service response for client
            event.getStore().selectClientTask();
            HttpBase responseOut = transformResponse(event.getStore().selectTask(Task.CLIENT_TASK), requestOut, channel);

            for (Header header : responseOut.getResponseHeaders().getHeaders()) {
                resp.addHeader(header.getName(), header.getAllValuesAsString());
            }
            byte[] response = responseOut.getResponse();
            if (response != null) {
                resp.getOutputStream().write(response);
            }
            log.info("OK");
        } catch (Throwable t) {
            log.error(ExceptionUtils.getStackTrace(t));
            resp.setStatus(resp.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private static void logResponse(Task backSideTask, HttpBase requestOut) {
        // log response from backend service
        backSideTask.select();
        Headers responseHeaders = requestOut.getResponseHeaders();
        responseHeaders.setStatus(requestOut.getStatus());
        responseHeaders.setVerb(requestOut.getVerb());
        responseHeaders.setPathInfo(requestOut.getUri());
        backSideTask.getEventStore().putResponseHeader(responseHeaders);
        // TODO make this next line not seem to work
        //backSideTask.event._responseHeaders = requestOut._responseHeaders
        logResponseBody(backSideTask, requestOut);
        log.info("==> " + requestOut.getStatus() + " " + ((requestOut.getResponse() != null) ? requestOut.getResponseContentType() + " " + requestOut.getResponse().length + " bytes" : "NULL"));
    }

    static Headers getRequestHeaders(HttpServletRequest req, Verb verb) {
        List<String> names = Collections.list(req.getHeaderNames());
        Map<String, List<String>> hdrs = new HashMap<>();
        for (String name : names) {
            List<String> values = Collections.list(req.getHeaders(name));
            hdrs.put(name, values);
        }
        Headers headers = new Headers(hdrs);
        headers.setVerb(verb.toString());
        try {
            headers.setPathInfo(new URI(req.getPathInfo()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return headers;
    }

    static HttpBase logClientRequestIn(Event event, Headers headers, byte[] body, Verb verb) {
        HttpBase base = (verb == Verb.GET) ? new HttpGet() : new HttpPost();
        event.getStore().selectClientTask();
        event.getStore().putRequestHeader(headers);
        base.setRequestHeaders(headers);

        event.getStore().putRequestBody(body);
        base.setRequest(body);
        String encoding = (headers.getContentEncoding().getAllValues().isEmpty()) ? "" : headers.getContentEncoding().getAllValues().get(0);
        if (encoding.equalsIgnoreCase("gzip")) {
            String txt = Gzip.decompressGZIP(body);
            event.getStore().putRequestBodyText(txt);
            base.setRequestText(txt);
        } else if (headers.getContentType().getAllValues().get(0).equalsIgnoreCase("text/html")) {
            event.getStore().putRequestHTMLBody(body);
            base.setRequestText(new String(body));
        } else if (isStringType(headers.getContentType().getAllValues().get(0))) {
            event.getStore().putRequestBodyText(new String(body));
            base.setRequestText(new String(body));
        }
        return base;
    }

    private static List<String> stringTypes = Arrays.asList(
            "application/fhir+json",
            "application/json+fhir"
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

    static void logRequestBody(Event event, Headers headers, HttpBase http, HttpServletRequest req) {
        byte[] bytes;
        try {
            bytes = IOUtils.toByteArray(req.getInputStream());
        } catch (Exception e) {
            throw new  RuntimeException(e);
        }
        event.getStore().putRequestBody(bytes);
        http.setRequest(bytes);
        String encoding = headers.getContentEncoding().getAllValues().get(0);
        if (encoding.equalsIgnoreCase("gzip")) {
            String txt = Gzip.decompressGZIP(bytes);
            event.getStore().putRequestBodyText(txt);
            http.setRequest(txt.getBytes());
        } else if (headers.getContentType().getAllValues().get(0).equalsIgnoreCase("text/html")) {
            event.getStore().putRequestHTMLBody(bytes);
            http.setRequestText(new String(bytes));
        } else if (isStringType(headers.getContentType().getAllValues().get(0))) {
            http.setRequestText(new String(bytes));
        }
    }

    static void logResponseBody(Task task, HttpBase http) {
        task.select();
        Headers headers = http.getResponseHeaders();
        byte[] bytes = http.getResponse();
        task.getEventStore().putResponseBody(bytes);
        List<String> encodings = headers.getContentEncoding().getAllValues();
        if (encodings.isEmpty()) {
            if (isStringType(headers.getContentType().getAllValues().get(0))) {
                String txt = new String(bytes);
                http.setResponseText(txt);
                task.getEventStore().putResponseBodyText(txt);
            }
        }
        else {
            String encoding = encodings.get(0);
            if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
                String txt = Gzip.decompressGZIP(bytes);
                task.getEventStore().putResponseBodyText(txt);
                http.setResponseText(txt);
            } else if (headers.getContentType().getAllValues().get(0).equalsIgnoreCase("text/html")) {
                task.getEventStore().putResponseHTMLBody(bytes);
                http.setResponseText(new String(bytes));
            } else if (isStringType(headers.getContentType().getAllValues().get(0))) {
                http.setResponseText(new String(bytes));
            }
        }
    }

    static void logBackendRequest(Task task, HttpBase http) {
        task.select();
        Headers headers = http.getRequestHeaders();
        task.getEventStore().putRequestHeader(headers);
        byte[] bytes = http.getRequest();
        task.getEventStore().putRequestBody(bytes);
        List<String> encodings = headers.getContentEncoding().getAllValues();
        if (encodings.isEmpty()) {
            if (isStringType(headers.getContentType().getAllValues().get(0))) {
                String txt = new String(bytes);
                http.setRequestText(txt);
                task.getEventStore().putRequestBodyText(txt);
            }
        }
        else {
            String encoding = encodings.get(0);
            if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
                String txt = Gzip.decompressGZIP(bytes);
                task.getEventStore().putRequestBodyText(txt);
                http.setRequestText(txt);
            } else if (headers.getContentType().getAllValues().get(0).equalsIgnoreCase("text/html")) {
                task.getEventStore().putRequestHTMLBody(bytes);
                http.setRequestText(new String(bytes));
            } else if (isStringType(headers.getContentType().getAllValues().get(0))) {
                http.setRequestText(new String(bytes));
            }
        }
    }

    static HttpBase transformRequest(Task task, HttpPost requestIn, IBaseChannel channelTransform) {
        HttpPost requestOut = new HttpPost();

        channelTransform.transformRequest(requestIn, requestOut);

        task.select();
        logBackendRequest(task, requestOut);

        return requestOut;
    }

    static HttpBase transformRequest(Task task, HttpGet requestIn, IBaseChannel channelTransform) {
        HttpGet requestOut = new HttpGet();

        channelTransform.transformRequest(requestIn, requestOut);

        task.select();
        task.getEventStore().putRequestHeader(requestOut.getRequestHeaders());

        return requestOut;
    }

    static URI transformRequestUri(Task task, HttpBase requestIn, IBaseChannel channelTransform) {

        return channelTransform.transformRequestUrl(task.getEventStore().getEvent().getRequestHeaders().getPathInfo().getPath(), requestIn);

    }

    static HttpBase transformResponse(Task task, HttpBase responseIn, IBaseChannel channelTransform) {
        HttpBase responseOut = new HttpGet();  // here GET vs POST does not matter

        channelTransform.transformResponse(responseIn, responseOut);

        responseOut.getResponseHeaders().removeHeader("transfer-encoding");

        task.select();
        task.getEventStore().putResponseBody(responseOut.getResponse());
        task.getEventStore().putResponseHeader(responseOut.getResponseHeaders());
        logResponseBody(task, responseOut);

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
            simStore.deleteSim();
            return null;
        }

        //
        // everything above this is handling control operations
        // starting with this load of simStore, normal channel operations begin
        //

        simStore = new SimStore(externalCache, simId);

        // ChannelId has been established - from now all errors result in Event logging

        // the request targets a Channel - maybe a control message or a pass through.
        // pass through have Channel/ as the next element of the URI


        if (!uriParts.isEmpty()) {
            simStore.setChannel(uriParts.get(0).equals("Channel"));   // Channel -> message passes through to backend system
            uriParts.remove(0);
        }

        if (!uriParts.isEmpty()) {
            simStore.setResource(uriParts.get(0));
            uriParts.remove(0);
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

        if (type.equals("Event")) {
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
