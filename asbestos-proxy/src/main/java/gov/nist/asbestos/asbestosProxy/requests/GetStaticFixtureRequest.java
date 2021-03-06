package gov.nist.asbestos.asbestosProxy.requests;

// This returns static fixture to UI as UIEvent
// 0 - empty
// 1 - appContext
// 2 - "engine"
// 3 - "staticFixture"
// 4 - testCollectionId
// 5 - testId
// 6 - resourceType
// param url=relative path to file
// optional param fhirPath=path within bundle
// returns UIEvent as JSON

import com.google.gson.Gson;
import gov.nist.asbestos.client.events.UIEvent;
import gov.nist.asbestos.client.resolver.ResourceWrapper;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.URL;

public class GetStaticFixtureRequest {
    private static Logger log = Logger.getLogger(GetStaticFixtureRequest.class);

    private Request request;

    public static boolean isRequest(Request request) {
        return request.uriParts.size() == 7 && request.uriParts.get(3).equals("staticFixture");
    }

    public GetStaticFixtureRequest(Request request) {
        this.request = request;
    }

    public void run() {
        log.info("GetStaticFixtureRequest");

        String testCollectionId = request.uriParts.get(4);
        String testId = request.uriParts.get(5);
        String fixturePath = request.getParm("url");
        URL url = request.getFullUrl();
        if (url == null) {
            request.resp.setStatus(request.resp.SC_BAD_REQUEST);
            return;
        }
        String fhirPath = request.getParm("fhirPath");
        ResourceWrapper wrapper = request.ec.getStaticFixture(testCollectionId, testId, fixturePath, fhirPath, url);
        if (wrapper == null) {
            request.resp.setStatus(request.resp.SC_BAD_REQUEST);
            return;
        }
        //Returns.returnResource(request.resp, wrapper.getResource());
        UIEvent uiEvent = new UIEvent(request.ec).fromResource(wrapper);

        String json = new Gson().toJson(uiEvent);
        request.resp.setContentType("application/json");
        try {
            request.resp.getOutputStream().write(json.getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        request.resp.setStatus(request.resp.SC_OK);
    }
}
